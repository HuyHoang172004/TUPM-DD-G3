"""Aggregate raw run directories into run_metrics.csv, summary_metrics.csv, and RQ comparisons."""

from __future__ import annotations

import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path
from typing import Any

from parse_telemetry import (
    RUN_METRICS_COLUMNS,
    format_csv_value,
    linear_percentile,
    parse_run_directory,
    record_to_csv_row,
)

NUMERIC_METRICS: tuple[str, ...] = (
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
)

SUMMARY_COLUMNS: tuple[str, ...] = (
    "scenario_id",
    "variant",
    "cache_state",
    "context_source",
    "commit",
    "device",
    "api_level",
    "metric",
    "n",
    "median",
    "q1",
    "q3",
    "iqr",
    "min",
    "max",
    "failed_runs",
    "insufficient_repeats",
)

MIN_REQUIRED_REPEATS = 5


def compute_distribution(values: list[float]) -> dict[str, Any]:
    """Compute n, median, q1, q3, iqr (blank if n==1), min, max per CORE.md Section 4.5 & 11.2."""
    n = len(values)
    if n == 0:
        return {
            "n": 0,
            "median": None,
            "q1": None,
            "q3": None,
            "iqr": None,
            "min": None,
            "max": None,
        }
    ordered = sorted(float(v) for v in values)
    median = linear_percentile(ordered, 0.50)
    if n == 1:
        return {
            "n": 1,
            "median": median,
            "q1": ordered[0],
            "q3": ordered[0],
            "iqr": None,  # Explicitly blank when n == 1 per Section 4.5
            "min": ordered[0],
            "max": ordered[0],
        }
    q1 = linear_percentile(ordered, 0.25)
    q3 = linear_percentile(ordered, 0.75)
    iqr = (q3 - q1) if (q1 is not None and q3 is not None) else None
    return {
        "n": n,
        "median": median,
        "q1": q1,
        "q3": q3,
        "iqr": iqr,
        "min": ordered[0],
        "max": ordered[-1],
    }


def improvement_lower_is_better(baseline: float | None, optimized: float | None) -> float | None:
    """Compute (baseline - optimized) / baseline * 100%; return None (N/A) if baseline == 0."""
    if baseline is None or optimized is None or baseline == 0.0:
        return None
    return ((baseline - optimized) / baseline) * 100.0


def collect_runs(raw_dir: Path, allow_mock_fixtures: bool = False) -> list[dict[str, Any]]:
    """Scan raw_dir for run subdirectories and parse each valid run."""
    if not raw_dir.is_dir():
        return []
    records: list[dict[str, Any]] = []
    for child in sorted(raw_dir.iterdir()):
        if not child.is_dir():
            continue
        if not ((child / "metadata.json").is_file() or (child / "events.jsonl").is_file()):
            continue
        rec = parse_run_directory(child)
        if rec.get("is_mock_fixture") and not allow_mock_fixtures:
            continue
        if rec.get("is_warmup"):
            continue
        records.append(rec)
    return records


def build_summaries(records: list[dict[str, Any]]) -> tuple[list[dict[str, str]], dict[str, Any]]:
    """Group runs by (scenario_id, variant, cache_state, context_source, commit, device, api_level)."""
    grouped: dict[tuple[str, str, str, str, str, str, str], list[dict[str, Any]]] = defaultdict(list)
    for rec in records:
        key = (
            str(rec.get("scenario_id") or ""),
            str(rec.get("variant") or ""),
            str(rec.get("cache_state") or ""),
            str(rec.get("context_source") or "real"),
            str(rec.get("commit") or ""),
            str(rec.get("device") or ""),
            str(rec.get("api_level") if rec.get("api_level") is not None else ""),
        )
        grouped[key].append(rec)

    summary_rows: list[dict[str, str]] = []
    medians_by_context: dict[tuple[str, str, str, str, str, str], dict[str, dict[str, float | None]]] = (
        defaultdict(lambda: defaultdict(dict))
    )

    for key in sorted(grouped.keys()):
        scenario_id, variant, cache_state, context_source, commit, device, api_level = key
        runs = grouped[key]
        successful_runs = [r for r in runs if r.get("success") is True]
        failed_count = len(runs) - len(successful_runs)

        for metric in NUMERIC_METRICS:
            vals = [
                float(r[metric])
                for r in successful_runs
                if r.get(metric) is not None and r.get(metric) != ""
            ]
            if not vals and failed_count == 0:
                continue
            dist = compute_distribution(vals)
            n_val = int(dist["n"])
            insufficient = n_val < MIN_REQUIRED_REPEATS

            comp_key = (scenario_id, cache_state, context_source, commit, device, api_level)
            medians_by_context[comp_key][variant][metric] = dist["median"]

            summary_rows.append(
                {
                    "scenario_id": scenario_id,
                    "variant": variant,
                    "cache_state": cache_state,
                    "context_source": context_source,
                    "commit": commit,
                    "device": device,
                    "api_level": api_level,
                    "metric": metric,
                    "n": str(n_val),
                    "median": format_csv_value(dist["median"]),
                    "q1": format_csv_value(dist["q1"]),
                    "q3": format_csv_value(dist["q3"]),
                    "iqr": format_csv_value(dist["iqr"]),
                    "min": format_csv_value(dist["min"]),
                    "max": format_csv_value(dist["max"]),
                    "failed_runs": str(failed_count),
                    "insufficient_repeats": "true" if insufficient else "false",
                }
            )

    comparisons: list[dict[str, Any]] = []
    for comp_key, variant_map in sorted(medians_by_context.items()):
        scenario_id, cache_state, context_source, commit, device, api_level = comp_key
        # Never mix simulated runs into real resource savings conclusions
        v1 = variant_map.get("V1", {})
        v2 = variant_map.get("V2", {})
        v3 = variant_map.get("V3", {})

        rq1_v2_vs_v1: dict[str, Any] = {}
        rq2_v3_vs_v2: dict[str, Any] = {}

        for m in (
            "startup_ms",
            "frame_p95_ms",
            "peak_pss_mib",
            "request_p50_ms",
            "request_p95_ms",
            "image_p95_ms",
            "network_body_bytes",
            "prefetch_waste_pct",
        ):
            imp_rq1 = improvement_lower_is_better(v1.get(m), v2.get(m))
            if imp_rq1 is not None:
                rq1_v2_vs_v1[f"{m}_improvement_pct"] = round(imp_rq1, 2)
            imp_rq2 = improvement_lower_is_better(v2.get(m), v3.get(m))
            if imp_rq2 is not None:
                rq2_v3_vs_v2[f"{m}_improvement_pct"] = round(imp_rq2, 2)

        # Jank rate is reported as absolute percentage-point delta (Section 4.5)
        if v1.get("jank_pct") is not None and v2.get("jank_pct") is not None:
            rq1_v2_vs_v1["jank_delta_pp"] = round(float(v2["jank_pct"]) - float(v1["jank_pct"]), 2)
        if v2.get("jank_pct") is not None and v3.get("jank_pct") is not None:
            rq2_v3_vs_v2["jank_delta_pp"] = round(float(v3["jank_pct"]) - float(v2["jank_pct"]), 2)

        # Guardrails evaluation for V3 vs V2 (Section 4.7 & 11.2)
        pss_imp = rq2_v3_vs_v2.get("peak_pss_mib_improvement_pct")
        bytes_imp = rq2_v3_vs_v2.get("network_body_bytes_improvement_pct")
        jank_delta_pp = rq2_v3_vs_v2.get("jank_delta_pp")
        req_p95_imp = rq2_v3_vs_v2.get("request_p95_ms_improvement_pct")

        guardrails = {
            "resource_saving_ge_15pct": (
                (pss_imp is not None and pss_imp >= 15.0)
                or (bytes_imp is not None and bytes_imp >= 15.0)
            )
            if context_source == "real"
            else None,
            "jank_increase_le_2pp": (jank_delta_pp <= 2.0) if jank_delta_pp is not None else None,
            "latency_p95_increase_le_10pct": (
                (req_p95_imp >= -10.0) if req_p95_imp is not None else None
            ),
        }

        comparisons.append(
            {
                "scenario_id": scenario_id,
                "cache_state": cache_state,
                "context_source": context_source,
                "commit": commit,
                "device": device,
                "api_level": api_level,
                "rq1_v2_vs_v1": rq1_v2_vs_v1,
                "rq2_v3_vs_v2": rq2_v3_vs_v2,
                "guardrails_v3_vs_v2": guardrails,
            }
        )

    return summary_rows, {"total_runs": len(records), "comparisons": comparisons}


def aggregate_to_directory(
    raw_dir: Path, out_dir: Path, allow_mock_fixtures: bool = False
) -> dict[str, Any]:
    """Read all runs in raw_dir and write run_metrics.csv, summary_metrics.csv, and comparison_report.json."""
    out_dir.mkdir(parents=True, exist_ok=True)
    records = collect_runs(raw_dir, allow_mock_fixtures=allow_mock_fixtures)

    run_metrics_path = out_dir / "run_metrics.csv"
    with run_metrics_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(RUN_METRICS_COLUMNS))
        writer.writeheader()
        for rec in records:
            writer.writerow(record_to_csv_row(rec))

    summary_rows, report = build_summaries(records)
    summary_path = out_dir / "summary_metrics.csv"
    with summary_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(SUMMARY_COLUMNS))
        writer.writeheader()
        for row in summary_rows:
            writer.writerow(row)

    report_path = out_dir / "comparison_report.json"
    if records:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    return {
        "runs_processed": len(records),
        "summary_rows": len(summary_rows),
        "run_metrics_csv": str(run_metrics_path),
        "summary_metrics_csv": str(summary_path),
    }


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(
        description="Aggregate raw Adaptive News runs into processed CSVs."
    )
    parser.add_argument(
        "--raw-dir",
        type=Path,
        default=repo_root / "experiments" / "raw",
        help="Directory containing raw <run_id> subfolders",
    )
    parser.add_argument(
        "--out-dir",
        type=Path,
        default=repo_root / "experiments" / "processed",
        help="Directory for run_metrics.csv and summary_metrics.csv",
    )
    parser.add_argument(
        "--allow-mock-fixtures",
        action="store_true",
        help="Allow runs marked is_mock_fixture=true (only for pipeline verification)",
    )
    args = parser.parse_args()

    result = aggregate_to_directory(
        raw_dir=args.raw_dir,
        out_dir=args.out_dir,
        allow_mock_fixtures=args.allow_mock_fixtures,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
