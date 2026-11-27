import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from pathlib import Path
from unittest.mock import AsyncMock, patch

from .bootstrap import check_dataset
from .runner import source_revision


class BootstrapTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / "backend/generated"
        self.data.mkdir(parents=True)
        manifest = {"record_count": 1, "seed": 42, "dataset_sha256": "fixture", "image_bytes_by_width": {}}
        for path in (self.root / "backend/dataset-manifest.json", self.data / "manifest.json"):
            path.write_text(json.dumps(manifest), encoding="utf-8")
        with closing(sqlite3.connect(self.data / "articles.sqlite3")) as db, db:
            db.executescript("CREATE TABLE articles(id INTEGER); INSERT INTO articles VALUES(1); CREATE TABLE images(article_id INTEGER, width INTEGER, byte_count INTEGER);")
            for width in (360, 720, 1080):
                image = self.data / "images" / str(width) / "1.webp"
                image.parent.mkdir(parents=True)
                image.write_bytes(b"image")
                db.execute("INSERT INTO images VALUES(1, ?, 5)", (width,))

    def test_complete_dataset_is_accepted(self):
        self.assertTrue(check_dataset(self.root)[0])

    def test_missing_image_is_rejected_despite_valid_manifest(self):
        (self.data / "images/720/1.webp").unlink()
        self.assertFalse(check_dataset(self.root)[0])

    def test_truncated_image_is_rejected(self):
        (self.data / "images/360/1.webp").write_bytes(b"bad")
        self.assertFalse(check_dataset(self.root)[0])

    def test_noncanonical_manifest_is_rejected(self):
        (self.data / "manifest.json").write_text('{"record_count": 1}')
        self.assertFalse(check_dataset(self.root)[0])


class SourceRevisionTests(unittest.IsolatedAsyncioTestCase):
    async def test_missing_git_does_not_prevent_a_run_or_invent_revision(self):
        with patch("lab.runner.process", new=AsyncMock(side_effect=FileNotFoundError)):
            result = await source_revision()
        self.assertIsNone(result["commit"])
        self.assertIsNone(result["dirty"])
        self.assertEqual(result["source_control"], "unavailable")

    async def test_zip_checkout_does_not_invent_revision(self):
        with patch("lab.runner.process", new=AsyncMock(return_value="")):
            self.assertIsNone((await source_revision())["commit"])

    async def test_git_checkout_retains_commit_and_dirty_flag(self):
        with patch("lab.runner.process", new=AsyncMock(side_effect=["a" * 40, " M README.md"])):
            result = await source_revision()
        self.assertEqual(result["commit"], "a" * 40)
        self.assertTrue(result["dirty"])


if __name__ == "__main__":
    unittest.main()
