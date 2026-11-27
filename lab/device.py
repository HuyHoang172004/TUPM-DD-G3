"""ADB access uses argv only; the browser cannot submit shell commands."""
import asyncio
import json
import os
import re
import shutil
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.adaptivenews.app"
BENCHMARK_PACKAGE = "com.adaptivenews.benchmark"
AUTHORITY = PACKAGE + ".benchmark"
SAFE_ID = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")


def adb_path():
    candidates = [os.environ.get("LAB_ADB"), shutil.which("adb")]
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        if os.environ.get(name):
            candidates.append(str(Path(os.environ[name]) / "platform-tools" / "adb.exe"))
    candidates.extend([str(Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe"),
                       str(ROOT / ".lab/android-sdk/platform-tools/adb.exe")])
    return next((x for x in candidates if x and Path(x).is_file()), None)


async def process(*args, timeout=30, check=True):
    proc = await asyncio.create_subprocess_exec(*map(str, args), stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    try:
        out, err = await asyncio.wait_for(proc.communicate(), timeout)
    except BaseException:
        if proc.returncode is None:
            proc.kill()
        await proc.wait()
        raise
    if check and proc.returncode:
        raise RuntimeError((err or out).decode("utf-8", errors="replace")[:2000])
    return out.decode("utf-8", errors="replace").strip()


class Device:
    def __init__(self, serial, executable=None):
        self.serial = serial
        self.executable = executable or adb_path()
        if not self.executable or not re.fullmatch(r"emulator-\d+", serial):
            raise ValueError("Chọn Android emulator đã kết nối qua ADB")

    async def adb(self, *args, timeout=30, check=True):
        return await process(self.executable, "-s", self.serial, *args, timeout=timeout, check=check)

    async def control(self, method, arg=None):
        args = ["shell", "content", "call", "--uri", f"content://{AUTHORITY}", "--method", method]
        if arg is not None:
            args += ["--arg", arg]
        output = await self.adb(*args)
        if "Error" in output or "Exception" in output or "ok=false" in output:
            raise RuntimeError(output[:2000])
        return output

    async def events(self, trial_id):
        if not SAFE_ID.fullmatch(trial_id):
            raise ValueError("Invalid trial ID")
        await self.control("flush")
        output = await self.adb("exec-out", "content", "read", "--uri", f"content://{AUTHORITY}/events/{trial_id}")
        try:
            rows = [json.loads(line) for line in output.splitlines() if line.strip()]
            if not rows or not all(isinstance(row, dict) and "event" in row for row in rows):
                raise ValueError("Empty/invalid telemetry")
        except ValueError as error:
            raise RuntimeError("Không đọc được JSONL telemetry của app: "+output[:200]) from error
        return output

    async def pss(self):
        output = await self.adb("shell", "dumpsys", "meminfo", PACKAGE)
        match = re.search(r"TOTAL PSS:\s*(\d+)", output) or re.search(r"^\s*TOTAL\s+(\d+)", output, re.MULTILINE)
        return int(match[1]) if match else None


async def preflight():
    executable = adb_path()
    checks = []
    devices = []
    checks.append(dict(id="adb", title="Android Platform Tools", ok=bool(executable), detail=executable or "Đặt LAB_ADB hoặc ANDROID_HOME; xem hướng dẫn cài SDK"))
    if executable:
        try:
            output = await process(executable, "devices", "-l", timeout=12)
            for line in output.splitlines():
                if re.match(r"emulator-\d+\s+device\b", line):
                    serial = line.split()[0]
                    d = Device(serial, executable)
                    api, fingerprint, avd = await asyncio.gather(
                        d.adb("shell", "getprop", "ro.build.version.sdk"),
                        d.adb("shell", "getprop", "ro.build.fingerprint"),
                        d.adb("emu", "avd", "name"))
                    package = await d.adb("shell", "pm", "path", PACKAGE)
                    instrumentation = await d.adb("shell", "pm", "list", "instrumentation")
                    bridge = await d.adb("shell", "content", "call", "--uri", f"content://{AUTHORITY}", "--method", "ping", check=False)
                    devices.append(dict(serial=serial, api_level=int(api), fingerprint=fingerprint, avd=avd.splitlines()[0],
                                        app_installed="package:" in package,
                                        benchmark_installed=BENCHMARK_PACKAGE in instrumentation,
                                        bridge_ready="protocol=1" in bridge))
        except (RuntimeError, OSError, asyncio.TimeoutError, ValueError) as error:
            checks.append(dict(id="adb_connection", title="Kết nối ADB", ok=False, detail=str(error)))
    checks.append(dict(id="emulator", title="Android emulator", ok=bool(devices), detail=f"{len(devices)} emulator kết nối"))
    checks.append(dict(id="apks", title="App benchmark & test APK", ok=any(d["bridge_ready"] and d["benchmark_installed"] and d["api_level"] >= 31 for d in devices),
                       detail="Cần API 31+ và hai APK trên cùng emulator"))
    manifest_path = Path(os.environ.get("ADAPTIVE_NEWS_DATA_DIR", str(ROOT / "backend/generated"))) / "manifest.json"
    manifest = None
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        pass
    dataset_valid = bool(manifest and manifest.get("record_count") == 10_000 and re.fullmatch(r"[a-f0-9]{64}", manifest.get("dataset_sha256", "")))
    checks.append(dict(id="dataset", title="Dataset tái lập", ok=dataset_valid, detail=str(manifest_path)+" · cần đủ 10.000 bài"))
    import httpx
    for name, url in [("backend", os.environ.get("LAB_BACKEND_URL", "http://127.0.0.1:8000") + "/health"),
                      ("proxy", "http://127.0.0.1:8001/_lab/health")]:
        try:
            async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
                response = await client.get(url)
                response.raise_for_status()
                if name == "backend" and (not dataset_valid or response.headers.get("X-Adaptive-News-Dataset") != manifest["dataset_sha256"]):
                    raise ValueError("Dataset backend không khớp manifest; dùng backend cùng checkout và dữ liệu chuẩn")
            ok, detail = True, url
        except (httpx.HTTPError, OSError, ValueError) as error:
            ok, detail = False, f"{url}: {error}"
        checks.append(dict(id=name, title="Backend tin tức" if name == "backend" else "Proxy đo mạng", ok=ok, detail=detail))
    return dict(ready=all(c["ok"] for c in checks), checks=checks, devices=devices, dataset=manifest,
                host=dict(platform=os.name, cpu_count=os.cpu_count()), checked_at=time.time())
