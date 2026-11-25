"""Read-only Adaptive News API for the generated dataset."""

import base64
import binascii
import hashlib
import json
import os
import re
import sqlite3
from contextlib import asynccontextmanager, closing
from pathlib import Path
from typing import Annotated

from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi import Path as RoutePath
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .generate import BACKEND, WIDTHS

SUMMARY_FIELDS = "id, title, summary, category, published_at, image_path"
ARTICLE_CACHE = "public, max-age=0, must-revalidate"
IMAGE_CACHE = "public, max-age=3600"
MAX_ID = (1 << 63) - 1
CURSOR_PATTERN = re.compile(r"[A-Za-z0-9_-]{54}")


def encode_cursor(article_id, query, dataset_hash):
    position = article_id.to_bytes(8, "big")
    check = hashlib.sha256(position + bytes.fromhex(dataset_hash) + query.encode()).digest()
    return base64.urlsafe_b64encode(position + check).decode().rstrip("=")


def decode_cursor(cursor, query, dataset_hash):
    try:
        if not CURSOR_PATTERN.fullmatch(cursor):
            raise ValueError
        data = base64.urlsafe_b64decode(cursor + "==")
        article_id = int.from_bytes(data[:8], "big")
        if not 1 <= article_id <= MAX_ID or cursor != encode_cursor(
            article_id, query, dataset_hash
        ):
            raise ValueError
        return article_id
    except (ValueError, binascii.Error) as error:
        raise HTTPException(400, "Cursor is invalid for this query or dataset") from error


def matches_etag(request, etag):
    for header in request.headers.getlist("if-none-match"):
        for tag in header.split(","):
            if tag.strip().removeprefix("W/") in (etag, "*"):
                return True
    return False


def cached_json(request, payload):
    response = JSONResponse(payload)
    etag = '"' + hashlib.sha256(response.body).hexdigest() + '"'
    headers = {"Cache-Control": ARTICLE_CACHE, "ETag": etag}
    if matches_etag(request, etag):
        return Response(status_code=304, headers=headers)
    response.headers.update(headers)
    return response


def create_app(dataset_dir=None):
    directory = Path(dataset_dir or os.environ.get("ADAPTIVE_NEWS_DATA_DIR")
                     or BACKEND / "generated").resolve()
    db_uri = (directory / "articles.sqlite3").as_uri() + "?mode=ro"

    def connect():
        db = sqlite3.connect(db_uri, uri=True)
        db.row_factory = sqlite3.Row
        return closing(db)

    @asynccontextmanager
    async def lifespan(application):
        manifest = json.loads((directory / "manifest.json").read_text(encoding="utf-8"))
        application.state.dataset_hash = manifest["dataset_sha256"]
        with connect() as db:
            db.execute("SELECT id FROM articles LIMIT 1").fetchone()
        yield

    application = FastAPI(lifespan=lifespan)

    @application.exception_handler(StarletteHTTPException)
    async def http_error(_request, error):
        return JSONResponse(
            {"error": "invalid_request", "message": str(error.detail)},
            status_code=error.status_code,
            headers=error.headers,
        )

    @application.exception_handler(RequestValidationError)
    async def validation_error(_request, error):
        detail = error.errors()[0]
        field = ".".join(str(part) for part in detail["loc"])
        return JSONResponse(
            {"error": "invalid_request", "message": f'{field}: {detail["msg"]}'},
            status_code=400,
        )

    @application.get("/health")
    def health():
        return {"status": "ok"}

    @application.get("/v1/articles")
    def page(
        request: Request,
        limit: Annotated[int, Query(ge=1, le=100)],
        cursor: str | None = None,
        q: str | None = None,
    ):
        query = (q or "").strip().casefold()
        dataset_hash = application.state.dataset_hash
        after = 0 if cursor is None else decode_cursor(cursor, query, dataset_hash)
        with connect() as db:
            rows = db.execute(
                f"SELECT {SUMMARY_FIELDS} FROM articles "
                "WHERE id > ? AND (? = '' OR instr(title_fold, ?) > 0) "
                "ORDER BY id ASC LIMIT ?",
                (after, query, query, limit + 1),
            ).fetchall()
        items = [dict(row) for row in rows[:limit]]
        next_cursor = encode_cursor(items[-1]["id"], query, dataset_hash) if len(rows) > limit else None
        return cached_json(request, {"items": items, "next_cursor": next_cursor})

    @application.get("/v1/articles/{id}")
    def detail(request: Request, id: Annotated[int, RoutePath(ge=1, le=MAX_ID)]):
        with connect() as db:
            row = db.execute(
                f"SELECT {SUMMARY_FIELDS}, body FROM articles WHERE id = ?", (id,)
            ).fetchone()
        if row is None:
            raise HTTPException(404, "Article not found")
        return cached_json(request, dict(row))

    @application.get("/v1/images/{id}")
    def image(
        request: Request, id: Annotated[int, RoutePath(ge=1, le=MAX_ID)], width: int = 360
    ):
        if width not in WIDTHS:
            raise HTTPException(400, "width must be 360, 720 or 1080")
        with connect() as db:
            row = db.execute(
                "SELECT etag FROM images WHERE article_id = ? AND width = ?", (id, width)
            ).fetchone()
        if row is None:
            raise HTTPException(404, "Image not found")
        etag = '"' + row["etag"] + '"'
        headers = {"Cache-Control": IMAGE_CACHE, "ETag": etag}
        if matches_etag(request, etag):
            return Response(status_code=304, headers=headers)
        path = directory / "images" / str(width) / f"{id}.webp"
        return FileResponse(path, media_type="image/webp", headers=headers)

    return application


app = create_app()
