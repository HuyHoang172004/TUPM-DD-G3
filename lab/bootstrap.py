"""Small read-only checks used before the local launcher starts its services."""
import argparse
import json
import sqlite3
from contextlib import closing
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check_dependencies(root=ROOT):
    for source in (root / "backend/requirements.txt", root / "lab/requirements.txt"):
        for line in source.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            name, expected = line.strip().split("==", 1)
            try:
                actual = version(name)
            except PackageNotFoundError:
                return False, f"Missing Python package: {name}"
            if actual != expected:
                return False, f"{name}: expected {expected}, installed {actual}"
    return True, "Python dependency versions match requirements."


def check_dataset(root=ROOT):
    generated = root / "backend/generated"
    try:
        canonical = json.loads((root / "backend/dataset-manifest.json").read_text(encoding="utf-8"))
        manifest = json.loads((generated / "manifest.json").read_text(encoding="utf-8"))
        if manifest != canonical:
            return False, "Dataset manifest does not match the canonical dataset."
        database = generated / "articles.sqlite3"
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as db:
            if db.execute("SELECT COUNT(*) FROM articles").fetchone()[0] != canonical["record_count"]:
                return False, "Dataset article count is incomplete."
            rows = db.execute("SELECT article_id, width, byte_count FROM images").fetchall()
            if len(rows) != canonical["record_count"] * 3:
                return False, "Dataset image index is incomplete."
            for article_id, width, byte_count in rows:
                image = generated / "images" / str(width) / f"{article_id}.webp"
                if image.stat().st_size != byte_count:
                    return False, f"Dataset image size mismatch: {width}/{article_id}"
    except (OSError, ValueError, sqlite3.Error) as error:
        return False, f"Dataset is missing or incomplete: {error}"
    return True, "Canonical dataset, SQLite rows and image file sizes checked."


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("check", choices=("dependencies", "dataset"))
    args = parser.parse_args()
    valid, detail = (check_dataset if args.check == "dataset" else check_dependencies)()
    print(detail)
    raise SystemExit(0 if valid else 2)


if __name__ == "__main__":
    main()
