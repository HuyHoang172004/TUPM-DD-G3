"""Loopback-only shaping proxy. A single scheduler limits aggregate response bytes."""
import asyncio
import hmac
import json
import os
import re
import time
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from .device import ROOT, SAFE_ID

RAW = Path(os.environ.get("LAB_RAW_DIR", str(ROOT / "experiments/raw")))


class Shape(BaseModel):
    run_id: str
    trial_id: str
    kbps: int = Field(ge=1, le=1_000_000)
    delay_ms: int = Field(ge=0, le=5000)
    blocked: bool = False


class Shaper:
    def __init__(self):
        self.config = None
        self.next_slot = 0.0
        self.lock = asyncio.Lock()
        self.active = 0

    async def pace(self, size, kbps):
        async with self.lock:
            now = time.monotonic()
            start = max(now, self.next_slot)
            self.next_slot = start + size * 8 / (kbps * 1000)
            until = self.next_slot
        await asyncio.sleep(max(0, until - time.monotonic()))


state = Shaper()


@asynccontextmanager
async def lifespan(app):
    async with httpx.AsyncClient(timeout=30, trust_env=False) as client:
        app.state.client = client
        yield


app = FastAPI(lifespan=lifespan)


@app.get("/_lab/health")
async def health():
    return {"ok": True, "active_requests": state.active}


@app.post("/_lab/configure")
async def configure(config: Shape, request: Request):
    expected = os.environ.get("LAB_PROXY_TOKEN", "")
    if not expected or not hmac.compare_digest(request.headers.get("X-Lab-Token", ""), expected):
        raise HTTPException(403, "Runner token required")
    if not SAFE_ID.fullmatch(config.run_id) or not SAFE_ID.fullmatch(config.trial_id):
        raise HTTPException(422, "Invalid ID")
    if state.active:
        raise HTTPException(409, "Wait for previous requests to drain")
    state.config = config
    state.next_slot = 0
    return {"ok": True}


@app.get("/v1/{path:path}")
async def forward(path: str, request: Request):
    if not re.fullmatch(r"articles(?:/\d+)?|images/\d+", path):
        raise HTTPException(404)
    config = state.config
    if config is None:
        raise HTTPException(503, "No active experiment")
    row = dict(trial_id=config.trial_id, path="/v1/"+path, query=str(request.url.query),
               started_monotonic=time.monotonic(), bytes_body=0, status=None)
    log = RAW / config.run_id / config.trial_id / "proxy.jsonl"
    log.parent.mkdir(parents=True, exist_ok=True)

    def save():
        row["ended_monotonic"] = time.monotonic()
        with log.open("a", encoding="utf-8") as out:
            out.write(json.dumps(row, ensure_ascii=False)+"\n")

    if config.blocked:
        row["status"] = 503
        save()
        raise HTTPException(503, "Backend blocked for offline experiment")
    state.active += 1
    try:
        await asyncio.sleep(config.delay_ms / 1000)
        headers = {key: request.headers[key] for key in ("if-none-match", "accept") if key in request.headers}
        base = os.environ.get("LAB_BACKEND_URL", "http://127.0.0.1:8000").rstrip("/")
        upstream = await app.state.client.send(app.state.client.build_request("GET", base+"/v1/"+path, params=request.query_params, headers=headers), stream=True)
        row["status"] = upstream.status_code
    except BaseException:
        state.active -= 1
        row["status"] = "upstream_error"
        save()
        raise

    async def stream():
        try:
            async for chunk in upstream.aiter_raw(16_384):
                await state.pace(len(chunk), config.kbps)
                row["bytes_body"] += len(chunk)
                yield chunk
        finally:
            await upstream.aclose()
            state.active -= 1
            save()

    response_headers = {key: upstream.headers[key] for key in ("etag", "cache-control", "content-type", "content-length") if key in upstream.headers}
    return StreamingResponse(stream(), status_code=upstream.status_code, headers=response_headers)
