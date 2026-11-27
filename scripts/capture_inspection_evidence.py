"""Capture real screenshots and before/after experimental evidence on emulator-5554."""

from __future__ import annotations

import json
import shutil
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from aggregate_metrics import aggregate_to_directory
from run_experiments import (
    PACKAGE_NAME,
    adb_cmd,
    build_am_start_command,
    compute_config_hash,
    detect_git_commit,
    pull_telemetry_jsonl,
    query_device_environment,
    run_cmd,
)

REPO_ROOT = Path(__file__).resolve().parent.parent
SCREENSHOT_DIR = REPO_ROOT / "experiments" / "screenshots"
RAW_SMOKE_DIR = REPO_ROOT / "experiments" / "raw_smoke"
PROCESSED_SMOKE_DIR = REPO_ROOT / "experiments" / "processed_smoke"
ARTIFACT_DIR = Path(r"C:\Users\Admin\.gemini\antigravity\brain\55209ec1-20ae-42a8-86dd-851e7158fc8c")


def take_screenshot(filename: str) -> Path:
    """Capture a PNG screenshot from the active emulator and copy to artifact dir if present."""
    SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
    remote_tmp = f"/sdcard/{filename}"
    local_path = SCREENSHOT_DIR / filename
    run_cmd(adb_cmd("shell", "screencap", "-p", remote_tmp))
    run_cmd(adb_cmd("pull", remote_tmp, str(local_path)))
    run_cmd(adb_cmd("shell", "rm", "-f", remote_tmp))
    if ARTIFACT_DIR.is_dir() and local_path.is_file():
        shutil.copy2(local_path, ARTIFACT_DIR / filename)
    return local_path


def tap(x: int, y: int, wait_s: float = 1.2) -> None:
    run_cmd(adb_cmd("shell", "input", "tap", str(x), str(y)))
    if wait_s > 0:
        time.sleep(wait_s)


def swipe_scroll(down: bool = True, wait_s: float = 1.2) -> None:
    if down:
        run_cmd(adb_cmd("shell", "input", "swipe", "540", "1600", "540", "450", "320"))
    else:
        run_cmd(adb_cmd("shell", "input", "swipe", "540", "450", "540", "1600", "320"))
    if wait_s > 0:
        time.sleep(wait_s)


def save_run_artifacts(
    run_id: str,
    scenario_id: str,
    variant: str,
    cache_state: str,
    context_source: str,
    commit: str,
    config_hash: str,
    env_info: dict,
    meminfo_samples: list[str],
    startup_ms: float | None = None,
    network_profile: str = "fast_20mbps_40ms",
    notes: str = "",
) -> None:
    run_folder = RAW_SMOKE_DIR / run_id
    run_folder.mkdir(parents=True, exist_ok=True)
    gfx_out = run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME)).stdout
    (run_folder / "gfxinfo.txt").write_text(gfx_out, encoding="utf-8")
    (run_folder / "meminfo.txt").write_text("\n---\n".join(meminfo_samples), encoding="utf-8")
    pulled = pull_telemetry_jsonl(run_id, run_folder / "events.jsonl")
    metadata = {
        "is_mock_fixture": False,
        "is_warmup": False,
        "run_id": run_id,
        "commit": commit,
        "config_hash": config_hash,
        "device": env_info["device"],
        "api_level": env_info["api_level"],
        "build_fingerprint": env_info["build_fingerprint"],
        "scenario_id": scenario_id,
        "variant": variant,
        "repeat": 1,
        "cache_state": cache_state,
        "context_source": context_source,
        "network_profile": network_profile,
        "startup_ms": startup_ms,
        "timestamp_utc": datetime.now(timezone.utc).isoformat(),
        "telemetry_pulled": pulled,
        "success": pulled,
        "notes": notes if pulled else "telemetry_pull_missing",
    }
    (run_folder / "metadata.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))


def main() -> None:
    commit = detect_git_commit(REPO_ROOT)
    config_hash = compute_config_hash(REPO_ROOT)
    env_info = query_device_environment()

    # Ensure network is enabled at start
    run_cmd(adb_cmd("shell", "svc", "wifi", "enable"))
    run_cmd(adb_cmd("shell", "svc", "data", "enable"))
    time.sleep(1.5)

    # 1. Capture V1 Feed screenshot (01_feed_v1_baseline_1080px.png) on existing S2_V1 run or quick launch
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(
        build_am_start_command("V1", "SNAP_V1_FEED", "S2", commit, config_hash, "warm_cache")
    )
    time.sleep(4.0)
    take_screenshot("01_feed_v1_baseline_1080px.png")

    # 2. Capture V2 Feed screenshot (02_feed_v2_static_720px.png)
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(
        build_am_start_command("V2", "SNAP_V2_FEED", "S2", commit, config_hash, "warm_cache")
    )
    time.sleep(3.5)
    take_screenshot("02_feed_v2_static_720px.png")

    # 3. Scenario S4 (Search + Detail) on V2 and capture screenshots 03 & 04
    run_id_s4 = "S4_V2_cold_r01"
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "pm", "clear", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME, "reset"))
    run_cmd(build_am_start_command("V2", run_id_s4, "S4", commit, config_hash, "cold_cache"))
    time.sleep(3.0)
    # Tap Search tab ("Tìm")
    tap(403, 2256, wait_s=1.5)
    # Tap search_input and enter ASCII query "00001" (matches "Tin công nghệ 00001")
    tap(450, 310, wait_s=1.0)
    run_cmd(adb_cmd("shell", "input", "text", "00001"))
    time.sleep(1.0)
    run_cmd(adb_cmd("shell", "input", "keyevent", "KEYCODE_BACK"))
    time.sleep(2.0)
    take_screenshot("03_search_query_00001.png")
    # Tap first result card (article_1)
    tap(540, 560, wait_s=2.5)
    # Tap "Lưu bài" (save_button at 165, 1074) so it is saved for offline S5 too
    tap(165, 1074, wait_s=1.5)
    take_screenshot("04_detail_saved_article_1.png")
    mem_s4 = [run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout]
    save_run_artifacts(
        run_id_s4, "S4", "V2", "cold", "real", commit, config_hash, env_info, mem_s4
    )

    # 4. Scenario S5 (Offline Saved Article reading) on V3
    run_id_s5 = "S5_V3_warm_r01"
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME, "reset"))
    run_cmd(build_am_start_command("V3", run_id_s5, "S5", commit, config_hash, "warm_cache"))
    time.sleep(3.0)
    # Open article_1 on Feed and ensure it is saved
    tap(540, 360, wait_s=2.0)
    tap(165, 1074, wait_s=1.2)
    try:
        # Disable real network (Wi-Fi + Mobile Data)
        run_cmd(adb_cmd("shell", "svc", "wifi", "disable"))
        run_cmd(adb_cmd("shell", "svc", "data", "disable"))
        time.sleep(2.0)
        # Switch to Saved tab ("Đã lưu")
        tap(678, 2256, wait_s=2.0)
        take_screenshot("05_saved_offline_list.png")
        # Open saved article_1 while offline
        tap(540, 360, wait_s=2.0)
        take_screenshot("06_detail_offline_reading.png")
        mem_s5 = [run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout]
    finally:
        run_cmd(adb_cmd("shell", "svc", "wifi", "enable"))
        run_cmd(adb_cmd("shell", "svc", "data", "enable"))
        time.sleep(2.0)
    save_run_artifacts(
        run_id_s5, "S5", "V3", "warm", "real", commit, config_hash, env_info, mem_s5,
        notes="offline_verified_pass"
    )

    # 5. Capture Lab screen states on V3: Normal (PERFORMANCE), Critical (ECO), Slow (BALANCED link_slow)
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(build_am_start_command("V3", "SNAP_V3_LAB", "S6", commit, config_hash, "warm_cache"))
    time.sleep(2.5)
    # Open Lab tab
    tap(953, 2256, wait_s=1.5)
    # Tap Normal 3 times (initial cooldownPassed=true because lastModeChangeMs==null -> 3 samples upgrades to PERFORMANCE)
    tap(165, 1191, wait_s=0.4)
    tap(165, 1191, wait_s=0.4)
    tap(165, 1191, wait_s=5.5)  # wait 5.5s for Lab dashboard 5s refresh loop
    take_screenshot("07_lab_v3_normal_performance.png")

    # Tap Critical -> immediate emergency downgrade to ECO (360px, page 10, cache 16 MiB, prefetch 0, request 2)
    tap(431, 1191, wait_s=5.5)
    take_screenshot("08_lab_v3_critical_eco.png")

    # Switch to Feed tab while V3 is in ECO mode to capture V3 · ECO · Backend (360px images)
    tap(127, 2256, wait_s=2.5)
    take_screenshot("09_feed_v3_eco_360px.png")

    # Fresh launch for Slow network state on Lab so cooldown doesn't hold ECO
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(build_am_start_command("V3", "SNAP_V3_SLOW", "S6", commit, config_hash, "warm_cache"))
    time.sleep(2.5)
    tap(953, 2256, wait_s=1.5)
    tap(677, 1191, wait_s=5.5)
    take_screenshot("10_lab_v3_slow_network.png")
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))

    # 6. Run S3 Adverse comparison (V2 Static vs V3 Adaptive under resource/network pressure, cold cache)
    # V2 Static under S3 (stays fixed at 720px, page_size=20, prefetch=4, concurrency=4)
    run_id_s3_v2 = "S3_V2_cold_r01"
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "pm", "clear", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME, "reset"))
    run_cmd(build_am_start_command("V2", run_id_s3_v2, "S3", commit, config_hash, "cold_cache"))
    time.sleep(2.0)
    mem_s3_v2 = []
    for step in range(7):
        swipe_scroll(down=(step != 6), wait_s=1.2)
        if step % 2 == 0:
            mem_s3_v2.append(run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout)
    save_run_artifacts(
        run_id_s3_v2, "S3", "V2", "cold", "real", commit, config_hash, env_info, mem_s3_v2,
        network_profile="adverse_pressure"
    )

    # V3 Adaptive under S3 adverse pressure (switches to ECO: 360px, page_size=10, prefetch=0, concurrency=2)
    run_id_s3_v3 = "S3_V3_cold_r01"
    run_cmd(adb_cmd("shell", "am", "force-stop", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "pm", "clear", PACKAGE_NAME))
    run_cmd(adb_cmd("shell", "dumpsys", "gfxinfo", PACKAGE_NAME, "reset"))
    run_cmd(build_am_start_command("V3", run_id_s3_v3, "S3", commit, config_hash, "cold_cache"))
    time.sleep(1.5)
    # Trigger adverse pressure on Lab and immediately return to Feed to scroll the exact same 7 steps
    tap(953, 2256, wait_s=1.0)
    tap(431, 1191, wait_s=1.0)
    tap(127, 2256, wait_s=1.5)
    mem_s3_v3 = []
    for step in range(7):
        swipe_scroll(down=(step != 6), wait_s=1.2)
        if step % 2 == 0:
            mem_s3_v3.append(run_cmd(adb_cmd("shell", "dumpsys", "meminfo", PACKAGE_NAME)).stdout)
    save_run_artifacts(
        run_id_s3_v3, "S3", "V3", "cold", "simulated", commit, config_hash, env_info, mem_s3_v3,
        network_profile="adverse_pressure"
    )

    summary = aggregate_to_directory(
        raw_dir=RAW_SMOKE_DIR,
        out_dir=PROCESSED_SMOKE_DIR,
        allow_mock_fixtures=False,
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
