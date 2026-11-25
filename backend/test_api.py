"""Functional HTTP verification using real Uvicorn and a generated dataset."""

import hashlib
import io
import json
import os
import socket
import threading
import time
import unittest
from http.client import HTTPConnection
from pathlib import Path
from tempfile import TemporaryDirectory
from urllib.parse import urlencode

import uvicorn
from PIL import Image

from .generate import COUNT, FIXTURES, WIDTHS, canonical, generate
from .main import create_app


class NewsApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        full = os.environ.get("ADAPTIVE_NEWS_TEST_DATA_DIR")
        if full:
            cls.directory = Path(full)
        else:
            temporary = TemporaryDirectory()
            cls.addClassCleanup(temporary.cleanup)
            cls.directory = Path(temporary.name)
            generate(cls.directory, 31)
        cls.manifest = json.loads((cls.directory / "manifest.json").read_text(encoding="utf-8"))
        cls.count = COUNT if full else 31
        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        cls.addClassCleanup(listener.close)
        cls.port = listener.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(create_app(cls.directory), log_level="error"))
        thread = threading.Thread(target=server.run, kwargs={"sockets": [listener]}, daemon=True)
        thread.start()

        def stop_server():
            server.should_exit = True
            thread.join(timeout=5)

        cls.addClassCleanup(stop_server)
        deadline = time.monotonic() + 10
        while not server.started and thread.is_alive() and time.monotonic() < deadline:
            time.sleep(0.02)
        if not server.started:
            raise RuntimeError("Uvicorn failed to start")

    def request(self, route, params=None, headers=None):
        connection = HTTPConnection("127.0.0.1", self.port, timeout=5)
        try:
            connection.request("GET", route + ("?" + urlencode(params) if params else ""),
                               headers=headers or {})
            response = connection.getresponse()
            return response.status, response.headers, response.read()
        finally:
            connection.close()

    def get_page(self, **params):
        status, _, body = self.request("/v1/articles", params)
        self.assertEqual(status, 200, body)
        return json.loads(body)

    def test_health_and_fixture_match(self):
        status, _, body = self.request("/health")
        self.assertEqual((status, json.loads(body)), (200, {"status": "ok"}))
        fixture = json.loads((FIXTURES / "success.json").read_text(encoding="utf-8"))
        self.assertEqual(len(fixture["items"]), 30)
        self.assertEqual(fixture["items"], self.get_page(limit=30)["items"])
        details = json.loads((FIXTURES / "details.json").read_text(encoding="utf-8"))
        for article in details:
            status, _, body = self.request(f'/v1/articles/{article["id"]}')
            self.assertEqual((status, json.loads(body)), (200, article))

    def test_all_pages_exact_order_without_bodies(self):
        for limit in (10, 20, 40, 100):
            ids = []
            cursor = None
            while True:
                params = {"limit": limit}
                if cursor is not None:
                    params["cursor"] = cursor
                page = self.get_page(**params)
                self.assertTrue(all("body" not in item for item in page["items"]))
                ids.extend(item["id"] for item in page["items"])
                cursor = page["next_cursor"]
                if cursor is None:
                    break
                self.assertLess(len(ids), self.count)
            self.assertEqual(ids, list(range(1, self.count + 1)))

    def test_search_and_cursor_validation(self):
        page = self.get_page(limit=2, q="  CÔNG NGHỆ  ")
        self.assertEqual([item["id"] for item in page["items"]], [1, 6])
        cursor = page["next_cursor"]
        next_page = self.get_page(limit=3, q="công nghệ", cursor=cursor)
        self.assertEqual([item["id"] for item in next_page["items"]], [11, 16, 21])
        self.assertEqual(self.get_page(limit=1, q="không có kết quả"), {
            "items": [], "next_cursor": None,
        })
        for route, params, expected in (
            ("/v1/articles", {"limit": 0}, 400),
            ("/v1/articles", {"limit": 101}, 400),
            ("/v1/articles", {"limit": 2, "cursor": cursor, "q": "khoa học"}, 400),
            ("/v1/articles", {"limit": 2, "cursor": cursor[:-1]}, 400),
            ("/v1/articles", {"limit": 2, "cursor": ""}, 400),
            ("/v1/articles/10001", None, 404),
            ("/v1/images/10001", {"width": 360}, 404),
            ("/v1/images/1", {"width": 361}, 400),
        ):
            status, _, body = self.request(route, params)
            self.assertEqual(status, expected)
            error = json.loads(body)
            self.assertEqual(error["error"], "invalid_request")
            self.assertTrue(error["message"])

    def test_conditional_cache_and_webp_dimensions(self):
        sizes = []
        for width in WIDTHS:
            route, params = "/v1/images/1", {"width": width}
            status, headers, body = self.request(route, params)
            self.assertEqual((status, headers["Content-Type"]), (200, "image/webp"))
            self.assertIn("max-age=", headers["Cache-Control"])
            self.assertEqual(headers["ETag"], f'"{hashlib.sha256(body).hexdigest()}"')
            with Image.open(io.BytesIO(body)) as picture:
                self.assertEqual(picture.size, (width, width * 2 // 3))
            sizes.append(len(body))
            cached = self.request(route, params, {"If-None-Match": "W/" + headers["ETag"]})
            self.assertEqual((cached[0], cached[2]), (304, b""))
        self.assertLess(sizes[0], sizes[1])
        self.assertLess(sizes[1], sizes[2])
        for route, params in (("/v1/articles", {"limit": 20}), ("/v1/articles/1", None)):
            _, headers, _ = self.request(route, params)
            status, _, body = self.request(route, params, {"If-None-Match": headers["ETag"]})
            self.assertEqual((status, body), (304, b""))

    def test_manifest_matches_exports(self):
        from contextlib import closing
        import sqlite3

        digest = hashlib.sha256()
        with closing(sqlite3.connect(self.directory / "articles.sqlite3")) as db:
            db.row_factory = sqlite3.Row
            for record in db.execute("""
                SELECT id, title, summary, category, published_at, image_path, body
                FROM articles ORDER BY id
            """):
                digest.update((canonical(dict(record)) + "\n").encode("utf-8"))
            categories = db.execute("SELECT COUNT(DISTINCT category) FROM articles").fetchone()[0]
        self.assertEqual(categories, 5)
        self.assertEqual(self.manifest["seed"], 42)
        self.assertEqual(self.manifest["record_count"], self.count)
        self.assertEqual(self.manifest["dataset_sha256"], digest.hexdigest())
        for width in WIDTHS:
            paths = list((self.directory / "images" / str(width)).glob("*.webp"))
            self.assertEqual(len(paths), self.count)
            self.assertEqual(
                sum(path.stat().st_size for path in paths),
                self.manifest["image_bytes_by_width"][str(width)],
            )
