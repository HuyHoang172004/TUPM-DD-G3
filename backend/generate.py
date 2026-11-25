"""Generate the canonical dataset and the small UI fixtures."""

import argparse
import hashlib
import json
import os
import random
import sqlite3
from concurrent.futures import ThreadPoolExecutor
from contextlib import closing, nullcontext
from datetime import datetime, timedelta, timezone
from itertools import islice
from pathlib import Path
from tempfile import TemporaryDirectory

from PIL import Image, ImageDraw

SEED = 42
COUNT = 10_000
WIDTHS = (360, 720, 1080)
BACKEND = Path(__file__).resolve().parent
FIXTURES = BACKEND.parent / "android" / "app" / "src" / "main" / "assets" / "news_fixture"
TOPICS = {
    "Technology": ("công nghệ", "thiết bị và dịch vụ số", (24, 75, 124)),
    "Science": ("khoa học", "nghiên cứu và môi trường", (27, 108, 87)),
    "Business": ("kinh doanh", "thị trường và sản xuất", (146, 68, 34)),
    "Culture": ("văn hóa", "nghệ thuật và di sản", (116, 53, 107)),
    "Sports": ("thể thao", "luyện tập và thi đấu", (132, 92, 25)),
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def records():
    rng = random.Random(SEED)
    start = datetime(2026, 1, 1, tzinfo=timezone.utc)
    categories = tuple(TOPICS)
    for article_id in range(1, COUNT + 1):
        category = categories[(article_id - 1) % len(categories)]
        label, subject, color = TOPICS[category]
        edition = rng.choice(("tổng quan", "góc nhìn", "chuyên đề", "điểm tin"))
        yield {
            "id": article_id,
            "title": f"Tin {label} {article_id:05d}",
            "summary": f"Bản tin {edition} về {subject}.",
            "category": category,
            "published_at": (start + timedelta(minutes=article_id - 1))
            .isoformat(timespec="seconds").replace("+00:00", "Z"),
            "image_path": f"/v1/images/{article_id}",
            "body": (
                f"Bản tin mẫu số {article_id:05d} thuộc nhóm {label}. "
                f"Ấn bản {edition} giới thiệu chủ đề {subject}.\n\n"
                "Nội dung tổng hợp dùng để kiểm thử hiển thị, tìm kiếm và đọc ngoại tuyến. "
                "Đoạn tiếp theo giúp phân biệt phần nội dung chi tiết với bản tóm tắt."
            ),
        }, color


def illustration(article_id, background):
    rng = random.Random(SEED + article_id)
    image = Image.new("RGB", (1080, 720), background)
    draw = ImageDraw.Draw(image)
    for index in range(32):
        x, y = rng.randrange(1080), rng.randrange(720)
        size = rng.randint(24, 270)
        fill = tuple((channel + rng.randrange(96)) % 256 for channel in background)
        if index % 2:
            draw.ellipse((x, y, x + size, y + size), fill=fill)
        else:
            draw.polygon(((x, y), (x + size, y - size // 3), (x, y + size)), fill=fill)
    return image


def render(task):
    output, article, color = task
    article_id = article["id"]
    source = illustration(article_id, color)
    images = []
    for width in WIDTHS:
        path = output / "images" / str(width) / f"{article_id}.webp"
        image = source if width == 1080 else source.resize(
            (width, width * 2 // 3), Image.Resampling.LANCZOS
        )
        image.save(path, "WEBP", quality=78, method=0)
        data = path.read_bytes()
        images.append((article_id, width, hashlib.sha256(data).hexdigest(), len(data)))
    return article, images


def generate(output, count=COUNT, jobs=1):
    output.mkdir(parents=True, exist_ok=True)
    for width in WIDTHS:
        (output / "images" / str(width)).mkdir(parents=True, exist_ok=True)
    image_bytes = {str(width): 0 for width in WIDTHS}
    digest = hashlib.sha256()
    executor = ThreadPoolExecutor(max_workers=jobs) if jobs > 1 else nullcontext()
    tasks = ((output, article, color) for article, color in islice(records(), count))
    with executor as pool, closing(sqlite3.connect(output / "articles.sqlite3")) as db, db:
        db.executescript("""
            CREATE TABLE articles (
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, title_fold TEXT NOT NULL,
                summary TEXT NOT NULL, category TEXT NOT NULL, published_at TEXT NOT NULL,
                image_path TEXT NOT NULL, body TEXT NOT NULL
            );
            CREATE TABLE images (
                article_id INTEGER NOT NULL, width INTEGER NOT NULL,
                etag TEXT NOT NULL, byte_count INTEGER NOT NULL,
                PRIMARY KEY (article_id, width)
            ) WITHOUT ROWID;
        """)
        rendered = map(render, tasks) if pool is None else pool.map(render, tasks)
        for article, images in rendered:
            digest.update((canonical(article) + "\n").encode("utf-8"))
            db.execute("""
                INSERT INTO articles VALUES (
                    :id, :title, :title_fold, :summary, :category,
                    :published_at, :image_path, :body
                )
            """, dict(article, title_fold=article["title"].casefold()))
            db.executemany("INSERT INTO images VALUES (?, ?, ?, ?)", images)
            for _, width, _, size in images:
                image_bytes[str(width)] += size
    manifest = {
        "seed": SEED,
        "record_count": count,
        "dataset_sha256": digest.hexdigest(),
        "image_bytes_by_width": image_bytes,
    }
    (output / "manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def write_fixture(directory):
    directory.mkdir(parents=True, exist_ok=True)
    details = [article for article, _color in islice(records(), 30)]
    summaries = [{key: value for key, value in row.items() if key != "body"} for row in details]
    samples = {
        "success.json": {"items": summaries, "next_cursor": None},
        "empty.json": {"items": [], "next_cursor": None},
        "error.json": {"error": "invalid_request", "message": "Fixture request failed"},
        "details.json": details,
    }
    for filename, payload in samples.items():
        (directory / filename).write_text(
            json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    illustration(0, (68, 86, 105)).resize(
        (360, 240), Image.Resampling.LANCZOS
    ).save(directory / "placeholder.webp", "WEBP", quality=78, method=0)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=BACKEND / "generated")
    parser.add_argument("--fixtures-only", action="store_true")
    parser.add_argument("--jobs", type=int, default=min(6, os.cpu_count() or 1))
    args = parser.parse_args()
    if args.fixtures_only:
        write_fixture(FIXTURES)
        return
    if args.output.exists():
        parser.error("output already exists; use a new output directory")
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with TemporaryDirectory(prefix=".dataset-", dir=args.output.parent) as staging:
        dataset = Path(staging) / "dataset"
        manifest = generate(dataset, jobs=args.jobs)
        dataset.rename(args.output)
        print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
