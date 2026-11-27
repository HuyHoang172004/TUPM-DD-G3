"""Serve the built dashboard and the local runner on the same loopback origin."""
import asyncio
import hashlib
import hmac
import json
import os
import secrets
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import FileResponse, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.middleware.trustedhost import TrustedHostMiddleware

from .catalog import public_catalog
from .device import ROOT, SAFE_ID, preflight
from .runner import Incomplete, Runner, TERMINAL


class RunRequest(BaseModel):
    method_id: str = Field(max_length=40)
    case_id: str = Field(max_length=40)
    device_serial: str = Field(pattern=r"^emulator-\d+$")
    mode: str = Field(default="demo", pattern="^(demo|report|long)$")
    network: str = Field(default="good", pattern="^(good|slow)$")


class CaptureReady(BaseModel):
    trial_id: str
    width: int = Field(ge=1, le=16384)
    height: int = Field(ge=1, le=16384)
    frame_rate: float = Field(ge=0, le=240)
    mime_type: str = Field(pattern=r"^video/webm(?:;codecs=[A-Za-z0-9,]+)?$")
    video_started_monotonic_ms: float
    viewport: dict[str, float]


class CancelRequest(BaseModel):
    reason: str = Field(default="user", pattern="^(user|evidence_lost)$")


def create_app(runner=None, manage_proxy=True):
    control = runner or Runner()
    token = secrets.token_urlsafe(32)

    @asynccontextmanager
    async def lifespan(app):
        control.load()
        if manage_proxy:
            await control.start_proxy()
        yield
        await control.close()

    app = FastAPI(title="Adaptive News Performance Lab", lifespan=lifespan)
    app.state.runner = control
    app.state.session_token = token
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=["127.0.0.1", "localhost", "testserver"])

    @app.middleware("http")
    async def local_requests(request: Request, call_next):
        if request.method not in ("GET", "HEAD", "OPTIONS"):
            if not hmac.compare_digest(request.headers.get("X-Lab-Token", ""), token):
                from fastapi.responses import JSONResponse
                return JSONResponse({"detail": "Local session token required"}, status_code=403)
            origin = request.headers.get("origin")
            if origin:
                from urllib.parse import urlsplit
                if urlsplit(origin).hostname not in ("127.0.0.1", "localhost"):
                    from fastapi.responses import JSONResponse
                    return JSONResponse({"detail": "Local origin required"}, status_code=403)
        response = await call_next(request)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Cache-Control"] = "no-store" if request.url.path.startswith("/api/") else "no-cache"
        return response

    def get_run(run_id):
        if not SAFE_ID.fullmatch(run_id) or run_id not in control.runs:
            raise HTTPException(404, "Không tìm thấy lượt chạy")
        return control.runs[run_id]

    def get_trial(run, trial_id):
        trial = next((t for t in run["trials"] if t["trial_id"] == trial_id), None)
        if not trial:
            raise HTTPException(404, "Không tìm thấy trial")
        return trial

    @app.get("/api/session")
    async def session():
        return {"token": token, "protocol": 1}

    @app.get("/api/instance")
    async def instance():
        return {"app": "adaptive-news-performance-lab", "workspace": str(ROOT), "pid": os.getpid()}

    @app.get("/api/preflight")
    async def check():
        return await preflight()

    @app.get("/api/methods")
    async def methods():
        return public_catalog()

    @app.get("/api/runs")
    async def runs():
        return sorted(control.runs.values(), key=lambda r: r["created_at"], reverse=True)[:100]

    @app.post("/api/runs", status_code=201)
    async def start(body: RunRequest):
        try:
            return await control.create(body)
        except ValueError as error:
            raise HTTPException(422, str(error)) from error
        except Incomplete as error:
            raise HTTPException(412, str(error)) from error
        except RuntimeError as error:
            raise HTTPException(409, str(error)) from error

    @app.get("/api/runs/{run_id}")
    async def status(run_id: str):
        return get_run(run_id)

    @app.post("/api/runs/{run_id}/heartbeat")
    async def heartbeat(run_id: str):
        get_run(run_id)
        if run_id in control.signals:
            control.signals[run_id]["heartbeat"] = time.monotonic()
        return {"ok": True}

    @app.post("/api/runs/{run_id}/cancel")
    async def cancel(run_id: str, body: CancelRequest = CancelRequest()):
        run = get_run(run_id)
        if run["status"] not in TERMINAL:
            control.signals[run_id]["evidence_lost" if body.reason == "evidence_lost" else "cancelled"] = True
        return {"ok": True}

    @app.post("/api/runs/{run_id}/capture-ready")
    async def ready(run_id: str, body: CaptureReady):
        run = get_run(run_id)
        trial = get_trial(run, body.trial_id)
        if trial["status"] != "awaiting_capture" or run_id not in control.signals:
            raise HTTPException(409, "Trial không chờ capture")
        trial["capture"] = body.model_dump()
        trial["capture"]["host_started_monotonic"] = time.monotonic()
        control.signals[run_id]["capture_ready"].add(body.trial_id)
        control.persist(run)
        return {"ok": True}

    @app.post("/api/runs/{run_id}/evidence")
    async def evidence(run_id: str, request: Request, trial_id: str, sequence: int = Query(ge=0), final: bool = False):
        run = get_run(run_id)
        trial = get_trial(run, trial_id)
        if run["status"] in TERMINAL:
            raise HTTPException(409, "Run đã kết thúc; raw được giữ nguyên")
        if trial["status"] not in ("running", "saving_video", "awaiting_capture"):
            raise HTTPException(409, "Trial không nhận video")
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > 16 * 1024 * 1024:
                raise HTTPException(413, "Video chunk vượt 16 MiB")
            chunks.append(chunk)
        data = b"".join(chunks)
        digest = hashlib.sha256(data).hexdigest()
        receipts = trial.setdefault("video_receipts", {})
        key = str(sequence)
        if key in receipts:
            if receipts[key] != digest:
                raise HTTPException(409, "Chunk lặp khác nội dung")
            return {"ok": True, "duplicate": True}
        if sequence != trial["video_sequence"]:
            raise HTTPException(409, "Sai thứ tự video chunk")
        path = control.raw / run_id / trial_id / "evidence.webm"
        if final and not data and (not path.exists() or path.stat().st_size == 0):
            raise HTTPException(422, "Không có video evidence")
        with path.open("ab") as out:
            out.write(data)
        receipts[key] = digest
        trial["video_sequence"] += 1
        if final:
            trial["video_finalized"] = True
            control.signals[run_id]["finalized"].add(trial_id)
        control.persist(run)
        return {"ok": True, "sequence": sequence, "finalized": final}

    @app.get("/api/runs/{run_id}/events")
    async def events(run_id: str, request: Request, after: int = Query(default=0, ge=0)):
        run = get_run(run_id)
        cursor = max(after, int(request.headers.get("Last-Event-ID", "0") or 0))

        async def stream():
            nonlocal cursor
            while not await request.is_disconnected():
                rows = control.events.get(run_id)
                if rows is None:
                    rows = []
                    path = control.raw / run_id / "progress.jsonl"
                    if path.exists():
                        rows = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
                    control.events[run_id] = rows
                for row in rows[cursor:]:
                    cursor = row["id"]
                    yield f"id: {cursor}\ndata: {json.dumps(row, ensure_ascii=False)}\n\n"
                if run["status"] in TERMINAL:
                    break
                yield ": heartbeat\n\n"
                await asyncio.sleep(.25)
        return StreamingResponse(stream(), media_type="text/event-stream", headers={"X-Accel-Buffering": "no"})

    @app.get("/api/runs/{run_id}/artifacts")
    async def artifacts(run_id: str):
        get_run(run_id)
        path = control.raw / run_id
        return [dict(name=f.relative_to(path).as_posix(), bytes=f.stat().st_size) for f in path.rglob("*") if f.is_file() and f.suffix != ".tmp"]

    @app.get("/api/runs/{run_id}/artifacts/{name:path}")
    async def artifact(run_id: str, name: str):
        run = get_run(run_id)
        root = (control.raw / run_id).resolve()
        file = (root / name).resolve()
        if not file.is_relative_to(root):
            raise HTTPException(403)
        if name == "evidence.zip":
            if run["status"] not in TERMINAL:
                raise HTTPException(409, "Đợi run kết thúc trước khi đóng gói")
            file = await asyncio.to_thread(control.archive, run_id)
        if not file.is_file():
            raise HTTPException(404)
        # HTML report opens as a separate document; never inject arbitrary raw HTML into dashboard.
        return FileResponse(file, filename=file.name if name.endswith((".zip", ".csv")) else None)

    dist = ROOT / "dashboard/dist"
    if dist.exists():
        app.mount("/", StaticFiles(directory=dist, html=True), name="dashboard")
    else:
        @app.get("/")
        async def missing_build():
            return {"detail": "Build dashboard: cd dashboard; npm ci; npm run build"}
    return app


app = create_app()
