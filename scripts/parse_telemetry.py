"""Parse raw telemetry JSONL, meminfo, and Macrobenchmark outputs for a single run."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
from pathlib import Path
from typing import Any

RUN_METRICS_COLUMNS: tuple[str, ...] = (
    "run_id",
    "commit",
    "device",
    "api_level",
    "scenario_id",
    "variant",
    "repeat",
    "cache_state",
    "context_source",
    "startup_ms",
    "frame_p95_ms",
    "jank_pct",
    "peak_pss_mib",
    "cpu_pct",
    "request_p50_ms",
    "request_p95_ms",
    "image_p95_ms",
    "network_body_bytes",
    "cache_hit_pct",
    "prefetch_waste_pct",
    "engine_p95_ms",
    "mode_switches",
    "energy_mwh",
    "success",
    "notes",
)

REQUIRED_EVENT_FIELDS: frozenset[str] = frozenset(
    {
        "schema_version",
        "run_id",
        "scenario_id",
        "variant",
        "event",
        "elapsed_ms",
        "mode",
        "source",
        "reason",
        "value",
        "unit",
        "attrs",
    }
)

TOTAL_PSS_PATTERNS: tuple[re.Regex[str], ...] = (
    re.compile(r"^\s*TOTAL\s+PSS:\s+(\d+)", re.IGNORECASE | re.MULTILINE),
    re.compile(r"^\s*TOTAL\s+(\d+)\s+\d+", re.IGNORECASE | re.MULTILINE),
)


def linear_percentile(values: list[float], p: float) -> float | None:
    """Compute percentile p in [0, 1] using linear interpolation at (n - 1) * p."""
    if not values:
        return None
    if not 0.0 <= p <= 1.0:
        raise ValueError(f"Percentile p must be in [0, 1], got {p}")
    ordered = sorted(float(v) for v in values)
    n = len(ordered)
    if n == 1:
        return ordered[0]
    pos = (n - 1) * p
    lo = int(math.floor(pos))
    hi = min(lo + 1, n - 1)
    frac = pos - lo
    return ordered[lo] + frac * (ordered[hi] - ordered[lo])


def format_csv_value(value: Any, decimals: int = 2) -> str:
    """Format a metric value for CSV output; None becomes empty string (never 0)."""
    if value is None or value == "":
        return ""
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if math.isnan(value) or math.isinf(value):
            return ""
        rounded = round(value, decimals)
        if rounded == int(rounded) and decimals == 0:
            return str(int(rounded))
        return f"{rounded:.{decimals}f}".rstrip("0").rstrip(".")
    return str(value)


def parse_meminfo_peak_pss_mib(meminfo_text: str) -> float | None:
    """Extract maximum TOTAL PSS (in KiB) from dumpsys meminfo text and convert to MiB."""
    samples_kib: list[int] = []
    for pattern in TOTAL_PSS_PATTERNS:
        for match in pattern.finditer(meminfo_text):
            samples_kib.append(int(match.group(1)))
    if not samples_kib:
        return None
    return max(samples_kib) / 1024.0


def parse_gfxinfo_frames(gfxinfo_text: str) -> tuple[float | None, int, int]:
    """Parse Total frames rendered and Janky frames from dumpsys gfxinfo output."""
    total_match = re.search(r"Total frames rendered:\s*(\d+)", gfxinfo_text)
    janky_match = re.search(r"Janky frames:\s*(\d+)", gfxinfo_text)
    p95_match = re.search(r"95th percentile:\s*(\d+(?:\.\d+)?)ms", gfxinfo_text)
    total_frames = int(total_match.group(1)) if total_match else 0
    janky_frames = int(janky_match.group(1)) if janky_match else 0
    p95_ms = float(p95_match.group(1)) if p95_match else None
    return p95_ms, total_frames, janky_frames


def parse_macrobenchmark_json(
    benchmark_data: dict[str, Any], scenario_id: str | None = None
) -> dict[str, float | None]:
    """Extract startup_ms, frame_p95_ms, and jank_pct from AndroidX Macrobenchmark JSON."""
    result: dict[str, float | None] = {
        "startup_ms": None,
        "frame_p95_ms": None,
        "jank_pct": None,
    }
    benchmarks = benchmark_data.get("benchmarks") or []
    for bench in benchmarks:
        if scenario_id and scenario_id.lower() not in str(bench.get("name", "")).lower():
            continue
        metrics = bench.get("metrics") or {}
        startup = metrics.get("timeToInitialDisplayMs")
        if isinstance(startup, dict) and startup.get("median") is not None:
            result["startup_ms"] = float(startup["median"])
        frame_cpu = metrics.get("frameDurationCpuMs")
        if isinstance(frame_cpu, dict):
            p95 = frame_cpu.get("P95") if "P95" in frame_cpu else frame_cpu.get("p95")
            if p95 is not None:
                result["frame_p95_ms"] = float(p95)
        overrun = metrics.get("frameOverrunMs")
        if isinstance(overrun, dict) and isinstance(overrun.get("runs"), list):
            runs = [float(x) for x in overrun["runs"] if isinstance(x, (int, float))]
            if runs:
                janky = sum(1 for x in runs if x > 0.0)
                result["jank_pct"] = (janky / len(runs)) * 100.0
        break
    return result


def validate_event_schema(obj: dict[str, Any], line_no: int) -> None:
    """Validate a single JSONL telemetry line against CORE.md Section 10 schema."""
    missing = REQUIRED_EVENT_FIELDS - set(obj.keys())
    if missing:
        raise ValueError(f"Line {line_no}: missing required telemetry fields {sorted(missing)}")
    if obj.get("schema_version") != 1:
        raise ValueError(f"Line {line_no}: unsupported schema_version={obj.get('schema_version')}")
    if not isinstance(obj.get("attrs"), dict):
        raise ValueError(f"Line {line_no}: 'attrs' must be a JSON object")


def parse_events_file(events_path: Path) -> dict[str, Any]:
    """Parse events.jsonl and compute all telemetry-derived metrics and diagnostics."""
    cpu_samples: list[float] = []
    request_latencies_ms: list[float] = []
    image_visible_ms: list[float] = []
    engine_eval_ms: list[float] = []
    search_ready_ms: list[float] = []
    detail_ready_ms: list[float] = []

    network_body_bytes = 0
    has_request_events = False
    valid_cache_lookups = 0
    cache_hits = 0

    # Prefetch tracking: key -> successful body bytes
    prefetch_success_bytes: dict[str, int] = {}
    prefetch_used_keys: set[str] = set()

    mode_switches = 0
    policy_changes_same_mode = 0
    saw_simulated = False
    has_run_start = False
    has_run_end = False

    envelope_run_id: str | None = None
    envelope_scenario_id: str | None = None
    envelope_variant: str | None = None
    commit_from_event: str | None = None
    reset_mode_from_event: str | None = None

    with events_path.open("r", encoding="utf-8") as handle:
        for line_no, raw_line in enumerate(handle, start=1):
            line = raw_line.strip()
            if not line:
                continue
            obj = json.loads(line)
            validate_event_schema(obj, line_no)

            envelope_run_id = envelope_run_id or obj.get("run_id")
            envelope_scenario_id = envelope_scenario_id or obj.get("scenario_id")
            envelope_variant = envelope_variant or obj.get("variant")

            if obj.get("source") == "simulated":
                saw_simulated = True

            event_name = obj["event"]
            value = obj.get("value")
            attrs: dict[str, Any] = obj.get("attrs") or {}

            if event_name == "run_start":
                has_run_start = True
                if attrs.get("commit"):
                    commit_from_event = str(attrs["commit"])
                if attrs.get("reset_mode"):
                    reset_mode_from_event = str(attrs["reset_mode"])

            elif event_name == "run_end":
                has_run_end = True

            elif event_name == "resource_snapshot":
                cpu_val = attrs.get("app_cpu_pct")
                if isinstance(cpu_val, (int, float)) and not isinstance(cpu_val, bool):
                    cpu_samples.append(float(cpu_val))

            elif event_name == "engine_eval":
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    engine_eval_ms.append(float(value))

            elif event_name == "policy_change":
                old_mode = attrs.get("old_mode")
                new_mode = obj.get("mode")
                if old_mode is not None and new_mode is not None and old_mode != new_mode:
                    mode_switches += 1
                else:
                    policy_changes_same_mode += 1

            elif event_name == "request_end":
                has_request_events = True
                body_bytes = attrs.get("bytes_body")
                if isinstance(body_bytes, (int, float)) and not isinstance(body_bytes, bool):
                    network_body_bytes += max(0, int(body_bytes))

                status = attrs.get("status")
                cache_source = str(attrs.get("cache_source") or "none")
                is_http_success = isinstance(status, int) and (200 <= status <= 299 or status == 304)
                if is_http_success:
                    if isinstance(value, (int, float)) and not isinstance(value, bool):
                        request_latencies_ms.append(float(value))
                    valid_cache_lookups += 1
                    if cache_source in ("cache", "conditional_cache"):
                        cache_hits += 1

            elif event_name == "image_visible":
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    image_visible_ms.append(float(value))

            elif event_name == "prefetch_end":
                key = attrs.get("key")
                success = attrs.get("success") is True
                if isinstance(key, str) and key and success:
                    byte_count = int(value) if isinstance(value, (int, float)) else 0
                    if byte_count > 0:
                        prefetch_success_bytes[key] = byte_count

            elif event_name == "prefetch_used":
                key = attrs.get("key")
                if isinstance(key, str) and key:
                    prefetch_used_keys.add(key)

            elif event_name == "search_ready":
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    search_ready_ms.append(float(value))

            elif event_name == "detail_ready":
                if isinstance(value, (int, float)) and not isinstance(value, bool):
                    detail_ready_ms.append(float(value))

    total_prefetch_bytes = sum(prefetch_success_bytes.values())
    unused_prefetch_bytes = sum(
        b for k, b in prefetch_success_bytes.items() if k not in prefetch_used_keys
    )
    prefetch_waste_pct = (
        (unused_prefetch_bytes / total_prefetch_bytes) * 100.0
        if total_prefetch_bytes > 0
        else None
    )
    cache_hit_pct = (
        (cache_hits / valid_cache_lookups) * 100.0 if valid_cache_lookups > 0 else None
    )
    cpu_pct = (sum(cpu_samples) / len(cpu_samples)) if cpu_samples else None

    return {
        "run_id": envelope_run_id,
        "scenario_id": envelope_scenario_id,
        "variant": envelope_variant,
        "commit": commit_from_event,
        "reset_mode": reset_mode_from_event,
        "context_source": "simulated" if saw_simulated else "real",
        "cpu_pct": cpu_pct,
        "request_p50_ms": linear_percentile(request_latencies_ms, 0.50),
        "request_p95_ms": linear_percentile(request_latencies_ms, 0.95),
        "request_count": len(request_latencies_ms),
        "image_p95_ms": linear_percentile(image_visible_ms, 0.95),
        "image_count": len(image_visible_ms),
        "network_body_bytes": network_body_bytes if has_request_events else None,
        "cache_hit_pct": cache_hit_pct,
        "valid_cache_lookups": valid_cache_lookups,
        "prefetch_waste_pct": prefetch_waste_pct,
        "total_prefetch_bytes": total_prefetch_bytes,
        "unused_prefetch_bytes": unused_prefetch_bytes,
        "engine_p50_ms": linear_percentile(engine_eval_ms, 0.50),
        "engine_p95_ms": linear_percentile(engine_eval_ms, 0.95),
        "engine_eval_count": len(engine_eval_ms),
        "mode_switches": mode_switches,
        "policy_changes_same_mode": policy_changes_same_mode,
        "search_ready_p50_ms": linear_percentile(search_ready_ms, 0.50),
        "search_ready_p95_ms": linear_percentile(search_ready_ms, 0.95),
        "detail_ready_p50_ms": linear_percentile(detail_ready_ms, 0.50),
        "detail_ready_p95_ms": linear_percentile(detail_ready_ms, 0.95),
        "has_run_start": has_run_start,
        "has_run_end": has_run_end,
    }


def parse_run_directory(run_dir: Path) -> dict[str, Any]:
    """Parse an entire experiments/raw/<run_id>/ directory into a normalized run record."""
    metadata_path = run_dir / "metadata.json"
    events_path = run_dir / "events.jsonl"
    meminfo_path = run_dir / "meminfo.txt"
    gfxinfo_path = run_dir / "gfxinfo.txt"
    benchmark_path = run_dir / "benchmark.json"

    metadata: dict[str, Any] = {}
    if metadata_path.is_file():
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))

    telemetry: dict[str, Any] = {}
    if events_path.is_file():
        telemetry = parse_events_file(events_path)

    scenario_id = str(metadata.get("scenario_id") or telemetry.get("scenario_id") or "")
    macro_metrics: dict[str, float | None] = {}
    if benchmark_path.is_file():
        bench_json = json.loads(benchmark_path.read_text(encoding="utf-8"))
        macro_metrics = parse_macrobenchmark_json(bench_json, scenario_id)

    peak_pss_mib = metadata.get("peak_pss_mib")
    if peak_pss_mib is None and meminfo_path.is_file():
        peak_pss_mib = parse_meminfo_peak_pss_mib(meminfo_path.read_text(encoding="utf-8"))

    frame_p95_ms = metadata.get("frame_p95_ms")
    if frame_p95_ms is None:
        frame_p95_ms = macro_metrics.get("frame_p95_ms")
    jank_pct = metadata.get("jank_pct")
    if jank_pct is None:
        jank_pct = macro_metrics.get("jank_pct")

    if (frame_p95_ms is None or jank_pct is None) and gfxinfo_path.is_file():
        gfx_p95, total_frames, janky_frames = parse_gfxinfo_frames(
            gfxinfo_path.read_text(encoding="utf-8")
        )
        if frame_p95_ms is None and gfx_p95 is not None:
            frame_p95_ms = gfx_p95
        if jank_pct is None and total_frames > 0:
            jank_pct = (janky_frames / total_frames) * 100.0

    startup_ms = metadata.get("startup_ms")
    if startup_ms is None:
        startup_ms = macro_metrics.get("startup_ms")

    context_source = metadata.get("context_source") or telemetry.get("context_source") or "real"
    if scenario_id == "S6":
        context_source = "simulated"

    cache_state = metadata.get("cache_state")
    if not cache_state:
        reset_mode = str(telemetry.get("reset_mode") or "")
        cache_state = "warm" if "warm" in reset_mode else "cold"

    notes_parts: list[str] = []
    if metadata.get("notes"):
        notes_parts.append(str(metadata["notes"]))
    if telemetry.get("search_ready_p50_ms") is not None:
        notes_parts.append(f"search_ready_p50_ms={telemetry['search_ready_p50_ms']:.1f}")
    if telemetry.get("detail_ready_p50_ms") is not None:
        notes_parts.append(f"detail_ready_p50_ms={telemetry['detail_ready_p50_ms']:.1f}")

    explicit_success = metadata.get("success")
    if explicit_success is None:
        success = bool(telemetry.get("has_run_start", False))
    else:
        success = bool(explicit_success)

    return {
        "run_id": metadata.get("run_id") or telemetry.get("run_id") or run_dir.name,
        "commit": metadata.get("commit") or telemetry.get("commit") or "",
        "device": metadata.get("device") or "",
        "api_level": metadata.get("api_level") if metadata.get("api_level") is not None else "",
        "scenario_id": scenario_id,
        "variant": metadata.get("variant") or telemetry.get("variant") or "",
        "repeat": metadata.get("repeat") if metadata.get("repeat") is not None else 1,
        "cache_state": cache_state,
        "context_source": context_source,
        "startup_ms": startup_ms,
        "frame_p95_ms": frame_p95_ms,
        "jank_pct": jank_pct,
        "peak_pss_mib": peak_pss_mib,
        "cpu_pct": metadata.get("cpu_pct") if metadata.get("cpu_pct") is not None else telemetry.get("cpu_pct"),
        "request_p50_ms": telemetry.get("request_p50_ms"),
        "request_p95_ms": telemetry.get("request_p95_ms"),
        "image_p95_ms": telemetry.get("image_p95_ms"),
        "network_body_bytes": (
            metadata.get("network_body_bytes")
            if metadata.get("network_body_bytes") is not None
            else telemetry.get("network_body_bytes")
        ),
        "cache_hit_pct": telemetry.get("cache_hit_pct"),
        "prefetch_waste_pct": telemetry.get("prefetch_waste_pct"),
        "engine_p95_ms": telemetry.get("engine_p95_ms"),
        "mode_switches": (
            telemetry.get("mode_switches")
            if telemetry.get("mode_switches") is not None
            else metadata.get("mode_switches")
        ),
        "energy_mwh": metadata.get("energy_mwh"),
        "success": success,
        "notes": "; ".join(notes_parts),
        "is_mock_fixture": bool(metadata.get("is_mock_fixture", False)),
        "is_warmup": bool(metadata.get("is_warmup", False)),
        "_diagnostics": telemetry,
    }


def record_to_csv_row(record: dict[str, Any]) -> dict[str, str]:
    """Convert a parsed run record into the exact 25-column CSV dictionary."""
    int_columns = {"api_level", "repeat", "network_body_bytes", "mode_switches"}
    row: dict[str, str] = {}
    for col in RUN_METRICS_COLUMNS:
        val = record.get(col)
        if col in int_columns and isinstance(val, (int, float)) and not isinstance(val, bool):
            row[col] = str(int(val))
        else:
            row[col] = format_csv_value(val, decimals=2)
    return row


def main() -> None:
    parser = argparse.ArgumentParser(description="Parse a single Adaptive News raw run folder.")
    parser.add_argument("run_dir", type=Path, help="Path to experiments/raw/<run_id>")
    parser.add_argument("--csv-out", type=Path, default=None, help="Optional single-row CSV output")
    args = parser.parse_args()

    record = parse_run_directory(args.run_dir)
    csv_row = record_to_csv_row(record)
    print(json.dumps(csv_row, ensure_ascii=False, indent=2))

    if args.csv_out:
        args.csv_out.parent.mkdir(parents=True, exist_ok=True)
        with args.csv_out.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=list(RUN_METRICS_COLUMNS))
            writer.writeheader()
            writer.writerow(csv_row)


if __name__ == "__main__":
    main()
