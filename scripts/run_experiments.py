"""Automated experiment runner for Adaptive News scenarios S1–S8 over adb."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from aggregate_metrics import aggregate_to_directory

PACKAGE_NAME = "com.adaptivenews.app"
MAIN_ACTIVITY = "com.adaptivenews.app/.ui.MainActivity"
SAFE_ID_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def resolve_adb_binary(explicit_adb: str | None = None) -> str:
    """Resolve adb executable from explicit path, PATH, ANDROID_SDK_ROOT, or D:\\And\\sdk."""
    if explicit_adb and Path(explicit_adb).is_file():
        return str(Path(explicit_adb))
    in_path = shutil.which("adb")
    if in_path:
        return in_path
    candidates = [
        Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools" / "adb.exe",
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / "adb.exe",
        Path(r"D:\And\sdk\platform-tools\adb.exe"),
        Path.home() / "AppData" / "Local" / "Android" / "Sdk" / "platform-tools" / "adb.exe",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    return "adb"


ADB_BIN = resolve_adb_binary()
ADB_SERIAL: str | None = None


def adb_cmd(*args: str) -> list[str]:
    base = [ADB_BIN]
    if ADB_SERIAL:
        base.extend(["-s", ADB_SERIAL])
    base.extend(args)
    return base


def sanitize_token(value: str, fallback: str) -> str:
    cleaned = re.sub(r"[^A-Za-z0-9_-]", "_", value)[:64]
    return cleaned if SAFE_ID_RE.match(cleaned) else fallback


def build_interleaved_schedule(
    scenarios: list[str],
    variants: list[str],
    cache_states: list[str],
    repeats: int,
    include_warmup: bool = True,
) -> list[dict[str, Any]]:
    """
    Generate an interleaved run schedule across V1, V2, V3 per CORE.md Section 11.1.
    Odd repeats run V1 -> V2 -> V3; even repeats run V3 -> V2 -> V1 to distribute thermal drift.
    """
    schedule: list[dict[str, Any]] = []
    for scenario_id in scenarios:
        applicable_caches = (
            ["warm"] if scenario_id in ("S5", "S6", "S8") else
            ["cold"] if scenario_id == "S7" else
            cache_states
        )
        applicable_variants = ["V3"] if scenario_id == "S6" else variants

        for cache_state in applicable_caches:
            if include_warmup:
                for variant in applicable_variants:
                    run_id = sanitize_token(
                        f"{scenario_id}_{variant}_{cache_state}_warmup", "warmup_run"
                    )
                    schedule.append(
                        {
                            "run_id": run_id,
                            "scenario_id": scenario_id,
                            "variant": variant,
                            "cache_state": cache_state,
                            "repeat": 0,
                            "is_warmup": True,
                        }
                    )

            for repeat in range(1, repeats + 1):
                ordered_variants = (
                    list(applicable_variants)
                    if repeat % 2 == 1
                    else list(reversed(applicable_variants))
                )
                for variant in ordered_variants:
                    run_id = sanitize_token(
                        f"{scenario_id}_{variant}_{cache_state}_r{repeat:02d}",
                        f"run_{repeat:02d}",
                    )
                    schedule.append(
                        {
                            "run_id": run_id,
                            "scenario_id": scenario_id,
                            "variant": variant,
                            "cache_state": cache_state,
                            "repeat": repeat,
                            "is_warmup": False,
                        }
                    )
    return schedule


def build_am_start_command(
    variant: str,
    run_id: str,
    scenario_id: str,
    commit: str,
    config_hash: str,
    reset_mode: str,
) -> list[str]:
    """Construct the exact adb shell am start command matching MainActivity.kt contract."""
    for name, val in (
        ("variant", variant),
        ("run_id", run_id),
        ("scenario_id", scenario_id),
        ("commit", commit),
        ("config_hash", config_hash),
        ("reset_mode", reset_mode),
    ):
        if not SAFE_ID_RE.match(val):
            raise ValueError(f"Invalid intent extra {name}={val!r}; must match [A-Za-z0-9_-]{{1,64}}")

    return adb_cmd(
        "shell",
        "am",
        "start",
        "-W",
        "-n",
        MAIN_ACTIVITY,
        "--es",
        "variant",
        variant,
        "--es",
        "run_id",
        run_id,
        "--es",
        "scenario_id",
        scenario_id,
        "--es",
        "commit",
        commit,
        "--es",
        "config_hash",
        config_hash,
        "--es",
        "reset_mode",
        reset_mode,
    )


def run_cmd(cmd: list[str], timeout: int = 60) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, check=False)


def detect_git_commit(repo_root: Path) -> str:
    res = run_cmd(["git", "-C", str(repo_root), "rev-parse", "--short=12", "HEAD"])
    if res.returncode == 0 and res.stdout.strip():
        return sanitize_token(res.stdout.strip(), "uncommitted")
    return "uncommitted"


def compute_config_hash(repo_root: Path) -> str:
    manifest_path = repo_root / "backend" / "dataset-manifest.json"
    payload = manifest_path.read_bytes() if manifest_path.is_file() else b"adaptive-news-v1"
    return hashlib.sha256(payload).hexdigest()[:16]


def query_device_environment() -> dict[str, Any]:
    model = run_cmd(adb_cmd("shell", "getprop", "ro.product.model")).stdout.strip() or "unknown_device"
    sdk_str = run_cmd(adb_cmd("shell", "getprop", "ro.build.version.sdk")).stdout.strip()
    fingerprint = run_cmd(adb_cmd("shell", "getprop", "ro.build.fingerprint")).stdout.strip()
    api_level = int(sdk_str) if sdk_str.isdigit() else 35
    return {
        "device": sanitize_token(model, "android_device"),
        "api_level": api_level,
        "build_fingerprint": fingerprint,
    }


def pull_telemetry_jsonl(run_id: str, dest_path: Path) -> bool:
    """
    Pull files/telemetry/<run_id>/events.jsonl from the target package.
    Falls back to Storage Access Framework export via Lab -> 'Xuất log JSONL' -> 'SAVE'
    on non-rooted :user Play Store emulator images where benchmark builds are non-debuggable.
    """
    remote_rel = f"files/telemetry/{run_id}/events.jsonl"
    direct = run_cmd(adb_cmd("pull", f"/data/data/{PACKAGE_NAME}/{remote_rel}", str(dest_path)))
    if direct.returncode == 0 and dest_path.is_file() and dest_path.stat().st_size > 0:
        return True
    via_exec = run_cmd(adb_cmd("shell", "run-as", PACKAGE_NAME, "cat", remote_rel))
    if via_exec.returncode == 0 and via_exec.stdout.strip():
        dest_path.write_text(via_exec.stdout, encoding="utf-8")
        return True
    via_su = run_cmd(adb_cmd("shell", "su", "0", "cat", f"/data/data/{PACKAGE_NAME}/{remote_rel}"))
    if via_su.returncode == 0 and via_su.stdout.strip():
        dest_path.write_text(via_su.stdout, encoding="utf-8")
        return True

    # Fallback: Trigger Lab -> export_button ("Xuất log JSONL") -> SAF "SAVE" button
    saf_remote = f"/sdcard/Download/adaptive-news-{run_id}.jsonl"
    run_cmd(adb_cmd("shell", "rm", "-f", saf_remote))
    # 1. Tap "Lab" tab in bottom navigation bar (bounds [826,2151][1080,2361])
    run_cmd(adb_cmd("shell", "input", "tap", "953", "2256"))
    time.sleep(1.2)
    # 2. Tap "Xuất log JSONL" (export_button, bounds [42,1432][425,1558])
    run_cmd(adb_cmd("shell", "input", "tap", "233", "1495"))
    time.sleep(2.0)
    # 3. Tap "SAVE" (android:id/button1 in DocumentsUI, bounds [812,2235][1043,2361])
    run_cmd(adb_cmd("shell", "input", "tap", "927", "2298"))
    time.sleep(1.5)
    via_saf = run_cmd(adb_cmd("pull", saf_remote, str(dest_path)))
    if via_saf.returncode == 0 and dest_path.is_file() and dest_path.stat().st_size > 0:
        run_cmd(adb_cmd("shell", "rm", "-f", saf_remote))
        return True
    return False


def execute_single_run(
    item: dict[str, Any],
    raw_dir: Path,
    commit: str,
    config_hash: str,
    env_info: dict[str, Any],
    network_profile: str,
    scroll_seconds: float = 60.0,
) -> dict[str, Any]:
    """Execute a single scenario run on a connected Android device/emulator."""
    run_id = item["run_id"]
    scenario_id = item["scenario_id"]
    variant = item["variant"]
    cache_state = item["cache_state"]
    repeat = item["repeat"]
    reset_mode = f"{cache_state}_cache"

    run_folder = raw_dir / run_id
    run_folder.mkdir(parents=True, exist_ok=True)

    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    if cache_state == "cold":
        run_cmd(adb_cmd("shell", "pm", "clear", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME, "reset"))

    start_cmd = build_am_start_command(
        variant=variant,
        run_id=run_id,
        scenario_id=scenario_id,
        commit=commit,
        config_hash=config_hash,
        reset_mode=reset_mode,
    )
    launch_res = run_cmd(start_cmd, timeout=30)
    startup_ms: float | None = None
    total_time_match = re.search(r"TotalTime:\s*(\d+)", launch_res.stdout)
    if total_time_match and scenario_id == "S1":
        startup_ms = float(total_time_match.group(1))

    meminfo_samples: list[str] = []
    if scenario_id == "S1":
        time.sleep(3.5)
        meminfo_samples.append(run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout)
    elif scenario_id in ("S2", "S3"):
        time.sleep(2.0)
        started = time.monotonic()
        step = 0
        while time.monotonic() - started < scroll_seconds:
            if step % 10 == 9:
                run_cmd(adb_cmd("shell", "input", "swipe", "500", "400", "500", "1500", "300"))
            else:
                run_cmd(adb_cmd("shell", "input", "swipe", "500", "1500", "500", "400", "300"))
            if step % 3 == 0:
                meminfo_samples.append(
                    run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout
                )
            time.sleep(1.2)
            step += 1
    elif scenario_id == "S6":
        time.sleep(2.0)
        # Open Lab tab
        run_cmd(adb_cmd("shell", "input", "tap", "953", "2256"))
        time.sleep(1.5)
        # Inject Normal -> Critical (immediate ECO downgrade) -> Slow -> Real state
        run_cmd(adb_cmd("shell", "input", "tap", "165", "1191"))
        time.sleep(5.5)
        run_cmd(adb_cmd("shell", "input", "tap", "431", "1191"))
        time.sleep(5.5)
        run_cmd(adb_cmd("shell", "input", "tap", "677", "1191"))
        time.sleep(5.5)
        meminfo_samples.append(run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout)
        run_cmd(adb_cmd("shell", "input", "tap", "267", "1343"))
        time.sleep(1.5)
    else:
        time.sleep(6.0)
        meminfo_samples.append(run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout)

    gfx_out = run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME)).stdout
    (run_folder / "gfxinfo.txt").write_text(gfx_out, encoding="utf-8")
    (run_folder / "meminfo.txt").write_text("\n---\n".join(meminfo_samples), encoding="utf-8")

    # Pull telemetry (either directly or via Lab -> SAF export while Activity is still alive)
    pulled = pull_telemetry_jsonl(run_id, run_folder / "events.jsonl")
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))

    metadata = {
        "is_mock_fixture": False,
        "is_warmup": bool(item.get("is_warmup", False)),
        "run_id": run_id,
        "commit": commit,
        "config_hash": config_hash,
        "device": env_info["device"],
        "api_level": env_info["api_level"],
        "build_fingerprint": env_info["build_fingerprint"],
        "scenario_id": scenario_id,
        "variant": variant,
        "repeat": repeat,
        "cache_state": cache_state,
        "context_source": "simulated" if scenario_id == "S6" else "real",
        "network_profile": network_profile,
        "startup_ms": startup_ms,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "telemetry_pulled": pulled,
        "success": launch_res.returncode == 0 and pulled,
        "notes": "" if pulled else "telemetry_pull_missing",
    }
    (run_folder / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return metadata


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Run Adaptive News scenarios S1–S8 over adb.")
    parser.add_argument(
        "--scenarios",
        nargs="+",
        default=["S1", "S2", "S3", "S4", "S5", "S6", "S7"],
        help="Scenarios to schedule (e.g. S1 S2 S3 S4 S5 S6 S7)",
    )
    parser.add_argument(
        "--variants",
        nargs="+",
        default=["V1", "V2", "V3"],
        help="Variants to compare (V1 V2 V3)",
    )
    parser.add_argument(
        "--cache-states",
        nargs="+",
        default=["cold", "warm"],
        help="Cache states (cold warm)",
    )
    parser.add_argument(
        "--repeats",
        type=int,
        default=5,
        help="Measured repeats per (scenario, variant, cache_state); minimum 5 recommended",
    )
    parser.add_argument(
        "--no-warmup",
        action="store_true",
        help="Skip uncounted warmup runs before measured repeats",
    )
    parser.add_argument(
        "--network-profile",
        default="fast_20mbps_40ms",
        help="Documented network shaping profile for metadata.json",
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=repo_root / "experiments" / "raw",
        help="Output directory for raw run data",
    )
    parser.add_argument(
        "--processed-dir",
        type=Path,
        default=repo_root / "experiments" / "processed",
        help="Output directory for aggregated CSVs",
    )
    parser.add_argument(
        "--device",
        default=None,
        help="Optional adb device serial (e.g. emulator-5554)",
    )
    parser.add_argument(
        "--adb-path",
        default=None,
        help="Optional path to adb executable (auto-detected by default)",
    )
    parser.add_argument(
        "--scroll-seconds",
        type=float,
        default=60.0,
        help="Duration in seconds for S2/S3 scrolling (60.0 for official runs)",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the interleaved execution plan and am start commands without running adb",
    )
    args = parser.parse_args()

    global ADB_BIN, ADB_SERIAL
    if args.adb_path:
        ADB_BIN = resolve_adb_binary(args.adb_path)
    if args.device:
        ADB_SERIAL = args.device

    commit = detect_git_commit(repo_root)
    config_hash = compute_config_hash(repo_root)
    schedule = build_interleaved_schedule(
        scenarios=args.scenarios,
        variants=args.variants,
        cache_states=args.cache_states,
        repeats=args.repeats,
        include_warmup=not args.no_warmup,
    )

    if args.dry_run:
        preview = []
        for entry in schedule:
            cmd = build_am_start_command(
                variant=entry["variant"],
                run_id=entry["run_id"],
                scenario_id=entry["scenario_id"],
                commit=commit,
                config_hash=config_hash,
                reset_mode=f"{entry['cache_state']}_cache",
            )
            preview.append({**entry, "adb_command": " ".join(cmd)})
        print(
            json.dumps(
                {
                    "total_scheduled_runs": len(preview),
                    "measured_runs": sum(1 for x in preview if not x["is_warmup"]),
                    "warmup_runs": sum(1 for x in preview if x["is_warmup"]),
                    "commit": commit,
                    "config_hash": config_hash,
                    "schedule_preview": preview[:12],
                },
                ensure_ascii=False,
                indent=2,
            )
        )
        return

    env_info = query_device_environment()
    executed = []
    for entry in schedule:
        meta = execute_single_run(
            item=entry,
            raw_dir=args.raw_dir,
            commit=commit,
            config_hash=config_hash,
            env_info=env_info,
            network_profile=args.network_profile,
            scroll_seconds=args.scroll_seconds,
        )
        executed.append(meta)

    summary = aggregate_to_directory(
        raw_dir=args.raw_dir,
        out_dir=args.processed_dir,
        allow_mock_fixtures=False,
    )
    print(json.dumps({"executed": len(executed), "aggregation": summary}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
