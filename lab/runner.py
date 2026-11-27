import asyncio
import base64
import hashlib
import json
import os
import platform
import secrets
import re
import time
import zipfile
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path

import httpx

from .analysis import benchmark_metrics, csv_report, html_report, metrics_from_events, read_jsonl, summarize
from .catalog import configuration, lookup
from .device import AUTHORITY, BENCHMARK_PACKAGE, PACKAGE, ROOT, Device, preflight, process

RAW = Path(os.environ.get("LAB_RAW_DIR", str(ROOT / "experiments/raw")))
TERMINAL = {"completed", "cancelled", "failed", "incomplete"}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(".tmp")
    temp.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temp.replace(path)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


async def source_revision():
    # ZIP checkouts and fresh Windows machines may not have Git.
    try:
        commit = await process("git", "-C", str(ROOT), "rev-parse", "HEAD", check=False)
        if re.fullmatch(r"[a-f0-9]{40,64}", commit):
            dirty = bool(await process("git", "-C", str(ROOT), "status", "--porcelain"))
            return dict(commit=commit, dirty=dirty, source_control="git")
    except (OSError, RuntimeError, asyncio.TimeoutError):
        pass
    return dict(commit=None, dirty=None, source_control="unavailable",
                source_note="Git or repository metadata unavailable; source revision is not verified.")


class Incomplete(RuntimeError):
    pass


class Runner:
    def __init__(self, raw=None):
        self.raw = Path(raw or RAW)
        self.runs = {}
        self.tasks = {}
        self.signals = {}
        self.events = {}
        self.lock = asyncio.Lock()
        self.proxy_token = secrets.token_urlsafe(32)
        self.video_token = secrets.token_urlsafe(32)
        self.proxy_process = None

    def load(self, recover=True):
        self.raw.mkdir(parents=True, exist_ok=True)
        for path in self.raw.glob("*/run.json"):
            try:
                run = json.loads(path.read_text(encoding="utf-8"))
                if recover and run["status"] not in TERMINAL:
                    run.update(status="incomplete", error="Runner đã khởi động lại trong khi đo")
                    for trial in run["trials"]:
                        if trial["status"] not in TERMINAL:
                            trial["status"] = "incomplete"
                    write_json(path, run)
                self.runs[run["id"]] = run
            except (ValueError, KeyError):
                continue

    def persist(self, run):
        write_json(self.raw / run["id"] / "run.json", run)

    def emit(self, run, kind, **payload):
        rows = self.events.setdefault(run["id"], [])
        event = deepcopy({**payload, "id": len(rows)+1, "type": kind, "host_monotonic": time.monotonic()})
        rows.append(event)
        with (self.raw / run["id"] / "progress.jsonl").open("a", encoding="utf-8") as output:
            output.write(json.dumps(event, ensure_ascii=False)+"\n")
        return event

    async def start_proxy(self):
        env = {**os.environ, "LAB_PROXY_TOKEN": self.proxy_token, "LAB_RAW_DIR": str(self.raw)}
        import sys
        log_path = ROOT / ".lab/proxy.log"
        log_path.parent.mkdir(exist_ok=True)
        self.proxy_log = log_path.open("ab")
        self.proxy_process = await asyncio.create_subprocess_exec(sys.executable, "-m", "uvicorn", "lab.proxy:app", "--host", "127.0.0.1", "--port", "8001", "--no-access-log", cwd=str(ROOT), env=env,
                                                                 stdout=self.proxy_log, stderr=self.proxy_log)
        for _ in range(30):
            if self.proxy_process.returncode is not None:
                raise RuntimeError("Không khởi động được proxy; kiểm tra cổng 8001 và .lab/proxy.log")
            try:
                async with httpx.AsyncClient(timeout=1, trust_env=False) as client:
                    response = await client.get("http://127.0.0.1:8001/_lab/health")
                    if response.is_success:
                        return
            except httpx.HTTPError:
                pass
            await asyncio.sleep(.2)
        raise RuntimeError("Proxy startup timeout")

    async def close(self):
        for task in self.tasks.values():
            task.cancel()
        await asyncio.gather(*self.tasks.values(), return_exceptions=True)
        if self.proxy_process and self.proxy_process.returncode is None:
            self.proxy_process.terminate()
            await self.proxy_process.wait()
        if hasattr(self, "proxy_log"):
            self.proxy_log.close()

    async def shape(self, run, trial, blocked=False):
        slow = run["network"] == "slow"
        config = dict(run_id=run["id"], trial_id=trial["trial_id"], kbps=1000 if slow else 20_000,
                      delay_ms=250 if slow else 40, blocked=blocked)
        async with httpx.AsyncClient(timeout=10, trust_env=False) as client:
            for _ in range(50):
                response = await client.post("http://127.0.0.1:8001/_lab/configure", json=config, headers={"X-Lab-Token": self.proxy_token})
                if response.status_code != 409:
                    response.raise_for_status()
                    return
                await asyncio.sleep(.1)
        raise RuntimeError("Request của lượt trước chưa kết thúc")

    async def create(self, request):
        async with self.lock:
            if any(r["status"] not in TERMINAL for r in self.runs.values()):
                raise RuntimeError("Emulator đang có một bài đo hoạt động")
            method, case = lookup(request.method_id, request.case_id)
            if case["journey"] == "background" and request.mode != "long":
                raise ValueError("Bài 3 giờ/nhánh cần chọn chế độ Quan sát dài")
            if request.mode == "long" and case["journey"] != "background":
                raise ValueError("Quan sát dài chỉ dành cho bài WorkManager 3 giờ/nhánh")
            ready = await preflight()
            device = next((d for d in ready["devices"] if d["serial"] == request.device_serial), None)
            if not ready["ready"] or not device or not device["bridge_ready"] or not device["benchmark_installed"] or device["api_level"] < 31:
                raise Incomplete("Preflight chưa đạt; cài SDK, emulator API 31+, dataset và hai APK trước khi đo")
            run_id = "R"+datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S")+"_"+secrets.token_hex(3)
            revision = await source_revision()
            run = dict(id=run_id, method_id=request.method_id, case_id=request.case_id, mode=request.mode,
                       network=request.network, device_serial=request.device_serial, status="preparing",
                       created_at=datetime.now(timezone.utc).isoformat(), trials=[], summary={}, error=None,
                       metadata=dict(**revision, device=device, host={"platform": platform.platform(), "cpu_count": os.cpu_count()},
                                     dataset=ready["dataset"], recording="host_window_capture", energy_mwh=None,
                                     observation="emulator_only", reset="cold_process_and_image_caches", network=dict(kbps=1000 if request.network == "slow" else 20000, added_response_delay_ms=250 if request.network == "slow" else 40)))
            run["metadata"]["compilation"] = "CompilationMode.Ignore; same installed APK; AB/BA order; warmup before every trial"
            self.runs[run_id] = run
            self.signals[run_id] = dict(heartbeat=time.monotonic(), cancelled=False, capture_ready=set(), finalized=set())
            self.persist(run)
            self.tasks[run_id] = asyncio.create_task(self.execute(run, method, case))
            return run

    def check(self, run):
        signal = self.signals[run["id"]]
        if signal.get("evidence_lost"):
            raise Incomplete("Mất video evidence trong khi đo")
        if signal["cancelled"]:
            raise asyncio.CancelledError()
        if time.monotonic()-signal["heartbeat"] > 12:
            raise Incomplete("Mất heartbeat/evidence từ trình duyệt")

    async def wait_signal(self, run, key, trial_id, timeout=20):
        deadline = time.monotonic()+timeout
        while trial_id not in self.signals[run["id"]][key]:
            self.check(run)
            if time.monotonic() > deadline:
                raise Incomplete("Không nhận được xác nhận video: "+key)
            await asyncio.sleep(.1)

    async def execute(self, run, method, case):
        device = Device(run["device_serial"])
        try:
            repeats = 10 if run["mode"] == "report" else 1
            phases = ["performance", "memory"] if "peak_pss_mib" in method["metrics"] else ["performance"]
            for repeat in range(1, repeats+1):
                for arm in (["A", "B"] if repeat % 2 else ["B", "A"]):
                    for phase in phases:
                        self.check(run)
                        config = configuration(method["id"], case["id"], arm)
                        trial_id = f"{run['id']}_{repeat}{arm}_{phase[0]}"
                        config.update(run_id=run["id"], trial_id=trial_id, method_id=method["id"], case_id=case["id"], arm=arm)
                        config_hash = fingerprint(config)
                        trial = dict(trial_id=trial_id, repeat=repeat, arm=arm, phase=phase, config=config,
                                     config_hash=config_hash, status="preparing", metrics={}, video_sequence=0, video_finalized=False)
                        run["trials"].append(trial)
                        path = self.raw / run["id"] / trial_id
                        write_json(path / "config.json", config)
                        await device.adb("shell", "am", "force-stop", PACKAGE)
                        # Prior worker must not contaminate the next branch.
                        await self.shape(run, trial)
                        encoded = base64.b64encode(json.dumps(config).encode()).decode()
                        await device.control("configure", encoded)
                        await device.control("reset")
                        await device.adb("shell", "am", "force-stop", PACKAGE)
                        self.emit(run, "warmup", trial_id=trial_id, message="Warmup riêng 2 giây, sau đó reset cache và process")
                        await device.adb("shell", "am", "start", "-W", "-n", PACKAGE+"/.ui.MainActivity",
                                         "--es", "variant", config["variant"], "--es", "run_id", "W_"+trial_id,
                                         "--es", "scenario_id", "warmup")
                        await asyncio.sleep(2)
                        await device.adb("shell", "am", "force-stop", PACKAGE)
                        await self.shape(run, trial)
                        await device.control("reset")
                        await device.adb("shell", "am", "force-stop", PACKAGE)
                        trial["status"] = "awaiting_capture"
                        run["status"] = "running"
                        self.persist(run)
                        self.emit(run, "capture_requested", trial=trial)
                        await self.wait_signal(run, "capture_ready", trial_id)
                        trial["status"] = "running"
                        self.persist(run)
                        self.emit(run, "trial_started", trial=trial)
                        await self.measure(run, trial, case, device, path)
                        trial["status"] = "saving_video"
                        self.emit(run, "capture_stop", trial_id=trial_id)
                        await self.wait_signal(run, "finalized", trial_id, timeout=40)
                        raw_events = await device.events(trial_id)
                        (path / "events.jsonl").write_text(raw_events+"\n", encoding="utf-8")
                        events = read_jsonl(path / "events.jsonl")
                        applied = [e for e in events if e.get("event") == "experiment_config"]
                        if not applied or any(applied[-1].get("attrs", {}).get(k) != v for k, v in config.items()):
                            raise Incomplete("Không có xác nhận app đã áp dụng cấu hình lượt đo")
                        inputs = [e["elapsed_ms"] for e in events if e.get("event") == "query_input"]
                        if method["id"] == "debounce" and case["id"] == "debounce" and any(b-a >= 300 for a, b in zip(inputs, inputs[1:])):
                            raise Incomplete("Khoảng cách nhập ký tự thực tế vượt 300 ms; không đủ điều kiện kiểm chứng debounce")
                        pss = read_jsonl(path / "pss.jsonl")
                        proxy = read_jsonl(path / "proxy.jsonl")
                        # Compare completed proxy requests within the app's measured interval.
                        window = trial.get("measurement_window", {})
                        proxy_measured = [r for r in proxy if window.get("start", 0) <= r.get("ended_monotonic", 0) <= window.get("end", float("inf"))]
                        pss = [sample for sample in pss if window.get("start", 0) <= sample["host_monotonic"] <= window.get("end", float("inf"))]
                        if phase == "memory" and not pss:
                            raise Incomplete("Không lấy được mẫu PSS trong khoảng đo RAM")
                        trial["metrics"] = metrics_from_events(events, pss, proxy_measured)
                        if phase == "performance":
                            captured = benchmark_metrics(path / "benchmark")
                            if case["journey"] != "background" and "startup_ms" not in captured:
                                raise Incomplete("Thiếu JSON kết quả Macrobenchmark; không xác nhận phép đo đã hoàn tất")
                            trial["metrics"].update(captured)
                            if case["journey"] != "background":
                                await self.trace_metrics(trial, path)
                        trial["status"] = "completed"
                        run["summary"] = summarize(run["trials"], method["metrics"])
                        self.persist(run)
                        self.emit(run, "trial_completed", trial=trial, summary=run["summary"])
            run["status"] = "completed"
        except asyncio.CancelledError:
            run.update(status="cancelled", error="Người dùng dừng bài đo")
        except Incomplete as error:
            run.update(status="incomplete", error=str(error))
        except Exception as error:
            run.update(status="failed", error=str(error))
        finally:
            final_status = run["status"]
            for trial in run["trials"][-1:]:
                if trial["status"] in ("running", "saving_video") and not trial.get("video_finalized"):
                    run["status"] = "saving_video"
                    trial["status"] = "saving_video"
                    self.persist(run)
                    self.emit(run, "capture_stop", trial_id=trial["trial_id"])
                    deadline = time.monotonic()+5
                    while trial["trial_id"] not in self.signals[run["id"]]["finalized"] and time.monotonic() < deadline:
                        await asyncio.sleep(.1)
            run["status"] = final_status
            for trial in run["trials"][-1:]:
                path = self.raw / run["id"] / trial["trial_id"]
                if not (path / "events.jsonl").exists():
                    try:
                        raw = await device.events(trial["trial_id"])
                        (path / "events.jsonl").write_text(raw+"\n", encoding="utf-8")
                    except Exception as error:
                        trial.setdefault("notes", []).append("Không lấy được telemetry cuối: "+str(error)[:200])
            try:
                await device.adb("shell", "am", "force-stop", PACKAGE, timeout=10)
            except Exception:
                pass
            for trial in run["trials"]:
                if trial["status"] not in TERMINAL:
                    trial["status"] = "incomplete"
            run["summary"] = summarize(run["trials"], method["metrics"])
            self.emit(run, "run_finished", status=run["status"], error=run.get("error"))
            self.persist(run)
            self.report(run, method)

    async def measure(self, run, trial, case, device, path):
        await device.adb("logcat", "-c")
        command = [device.executable, "-s", device.serial, "logcat", "-v", "raw", "AN_ACTION:I", "AN_EVENT:I", "*:S"]
        logger = await asyncio.create_subprocess_exec(*command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        sampling = True

        async def log_stream():
            with (path / "device_live.jsonl").open("a", encoding="utf-8") as out:
                while line := await logger.stdout.readline():
                    try:
                        row = json.loads(line.decode("utf-8"))
                    except (ValueError, UnicodeError):
                        continue
                    if row.get("trial_id") != trial["trial_id"] and row.get("run_id") != trial["trial_id"]:
                        continue
                    row["host_received_monotonic"] = time.monotonic()
                    out.write(json.dumps(row, ensure_ascii=False)+"\n")
                    out.flush()
                    if row.get("event") == "measure_start":
                        trial.setdefault("measurement_window", {})["start"] = row["host_received_monotonic"]
                    elif row.get("event") == "measure_end":
                        trial.setdefault("measurement_window", {})["end"] = row["host_received_monotonic"]
                    elif row.get("event") == "offline_block":
                        await self.shape(run, trial, blocked=True)
                        await device.control("offline_ack")
                    self.emit(run, "action" if "action" in row else "telemetry", trial_id=trial["trial_id"], data=row)

        async def sample_memory():
            while sampling:
                self.check(run)
                if trial["phase"] == "memory" and "start" in trial.get("measurement_window", {}) and "end" not in trial["measurement_window"]:
                    value = await device.pss()
                    if value is not None:
                        row = dict(host_monotonic=time.monotonic(), pss_kib=value)
                        with (path / "pss.jsonl").open("a", encoding="utf-8") as out:
                            out.write(json.dumps(row)+"\n")
                        self.emit(run, "pss", trial_id=trial["trial_id"], sample_monotonic=row["host_monotonic"], pss_kib=value)
                await asyncio.sleep(2)

        logs = asyncio.create_task(log_stream())
        memory = asyncio.create_task(sample_memory())
        duration = 60_000 if run["mode"] == "report" else 20_000
        if case["journey"] == "background":
            duration = 3 * 60 * 60 * 1000
        args = ["shell", "am", "instrument", "-w", "-r", "-e", "class", "com.adaptivenews.benchmark.PerformanceBenchmark#runJourney",
                "-e", "trial_id", trial["trial_id"], "-e", "run_id", run["id"], "-e", "variant", trial["config"]["variant"],
                "-e", "scenario_id", run["method_id"], "-e", "journey", case["journey"], "-e", "phase", trial["phase"],
                "-e", "duration_ms", str(duration), "-e", "config_hash", trial["config_hash"],
                "-e", "androidx.benchmark.suppressErrors", "EMULATOR", "-e", "additionalTestOutputDir", "/sdcard/Android/media/"+BENCHMARK_PACKAGE+"/"+trial["trial_id"],
                BENCHMARK_PACKAGE+"/androidx.test.runner.AndroidJUnitRunner"]
        instrument = asyncio.create_task(device.adb(*args, timeout=duration/1000+360))
        try:
            while not instrument.done():
                self.check(run)
                if logs.done():
                    logs.result()
                    raise Incomplete("Logcat kết thúc khi bài đo còn chạy")
                if memory.done():
                    memory.result()
                await asyncio.sleep(.25)
            output = await instrument
            (path / "instrumentation.txt").write_text(output, encoding="utf-8")
            if "FAILURES!!!" in output or "INSTRUMENTATION_FAILED" in output or "OK (1 test)" not in output:
                reason = re.search(r"INSTRUMENTATION_STATUS: stack=([^\r\n]+)", output)
                raise RuntimeError("Instrumentation không hoàn tất: "+(reason[1] if reason else output[-500:]))
            await asyncio.sleep(.3)
            if trial["phase"] == "performance":
                await device.adb("pull", "/sdcard/Android/media/"+BENCHMARK_PACKAGE+"/"+trial["trial_id"], str(path / "benchmark"), timeout=90)
        finally:
            sampling = False
            instrument.cancel()
            memory.cancel()
            if logger.returncode is None:
                logger.terminate()
            await logger.wait()
            logs.cancel()
            await asyncio.gather(instrument, memory, logs, return_exceptions=True)

    async def trace_metrics(self, trial, path):
        traces = list((path / "benchmark").rglob("*.perfetto-trace"))
        if not traces:
            trial.setdefault("notes", []).append("Không có trace; jank=N/A")
            return

        def calculate():
            from perfetto.trace_processor import TraceProcessor, TraceProcessorConfig
            binary = Path(os.environ.get("LAB_TRACE_PROCESSOR", str(ROOT / ".lab/tools/perfetto/trace_processor_shell.exe")))
            config = TraceProcessorConfig(bin_path=str(binary) if binary.is_file() else None, load_timeout=10)
            with TraceProcessor(trace=str(traces[-1]), config=config) as tp:
                rows = list(tp.query("SELECT COUNT(*) AS n, SUM(CASE WHEN jank_type != 'None' THEN 1 ELSE 0 END) AS janky FROM actual_frame_timeline_slice WHERE upid IN (SELECT upid FROM process WHERE name = 'com.adaptivenews.app')"))
                if rows and rows[0].n:
                    return rows[0].janky / rows[0].n * 100
            return None
        try:
            trial["metrics"]["jank_pct"] = await asyncio.wait_for(asyncio.to_thread(calculate), timeout=40)
        except Exception as error:
            trial.setdefault("notes", []).append("Jank=N/A: "+str(error)[:200])

    def report(self, run, method):
        path = self.raw / run["id"]
        (path / "run_metrics.csv").write_text(csv_report(run), encoding="utf-8-sig", newline="")
        (path / "report.html").write_text(html_report(run, method), encoding="utf-8")
        write_json(path / "summary.json", run["summary"])
        manifest = []
        for file in path.rglob("*"):
            if file.is_file() and file.name not in ("manifest.json", "evidence.zip"):
                digest = hashlib.sha256()
                with file.open("rb") as source:
                    for chunk in iter(lambda: source.read(1024*1024), b""):
                        digest.update(chunk)
                manifest.append(dict(path=file.relative_to(path).as_posix(), bytes=file.stat().st_size, sha256=digest.hexdigest()))
        write_json(path / "manifest.json", manifest)
        # Video may be large; build ZIP only on explicit download, outside measurement.

    def archive(self, run_id):
        path = self.raw / run_id
        archive = path / "evidence.zip"
        with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_STORED) as output:
            for file in path.rglob("*"):
                if file.is_file() and file != archive:
                    output.write(file, file.relative_to(path))
        return archive
