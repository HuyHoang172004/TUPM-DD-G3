"""Unit tests for Adaptive News measurement pipeline (parser, aggregator, runner schedule)."""

from __future__ import annotations

import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS_DIR.parent
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from aggregate_metrics import (
    SUMMARY_COLUMNS,
    aggregate_to_directory,
    compute_distribution,
    improvement_lower_is_better,
)
from parse_telemetry import (
    RUN_METRICS_COLUMNS,
    linear_percentile,
    parse_meminfo_peak_pss_mib,
    parse_run_directory,
    record_to_csv_row,
)
from run_experiments import build_am_start_command, build_interleaved_schedule


class AdaptiveNewsPipelineTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixtures_dir = REPO_ROOT / "experiments" / "fixtures" / "mock_runs"

    def test_run_metrics_25_columns_contract(self) -> None:
        expected = (
            "run_id,commit,device,api_level,scenario_id,variant,repeat,cache_state,"
            "context_source,startup_ms,frame_p95_ms,jank_pct,peak_pss_mib,cpu_pct,"
            "request_p50_ms,request_p95_ms,image_p95_ms,network_body_bytes,cache_hit_pct,"
            "prefetch_waste_pct,engine_p95_ms,mode_switches,energy_mwh,success,notes"
        ).split(",")
        self.assertEqual(list(RUN_METRICS_COLUMNS), expected)
        self.assertEqual(len(RUN_METRICS_COLUMNS), 25)

        processed_csv = REPO_ROOT / "experiments" / "processed" / "run_metrics.csv"
        self.assertTrue(processed_csv.is_file())
        with processed_csv.open("r", encoding="utf-8") as handle:
            reader = csv.reader(handle)
            header = next(reader)
            data_rows = list(reader)
        self.assertEqual(header, expected)
        # Verify every row in processed/run_metrics.csv conforms to the 25-column contract and is not a mock fixture
        for row in data_rows:
            self.assertEqual(len(row), 25)
            self.assertFalse(row[0].startswith("MOCK_"))

    def test_linear_percentile_and_iqr_rules(self) -> None:
        # Single sample: IQR must be None (rendered as empty string in CSV)
        dist_single = compute_distribution([42.0])
        self.assertEqual(dist_single["n"], 1)
        self.assertAlmostEqual(dist_single["median"], 42.0)
        self.assertIsNone(dist_single["iqr"])

        # Five samples: [10, 20, 30, 40, 50] -> pos(0.25)=1 -> 20, pos(0.75)=3 -> 40, IQR=20
        vals = [50.0, 10.0, 40.0, 20.0, 30.0]
        self.assertAlmostEqual(linear_percentile(vals, 0.25) or 0.0, 20.0)
        self.assertAlmostEqual(linear_percentile(vals, 0.50) or 0.0, 30.0)
        self.assertAlmostEqual(linear_percentile(vals, 0.75) or 0.0, 40.0)
        dist_five = compute_distribution(vals)
        self.assertAlmostEqual(dist_five["iqr"] or 0.0, 20.0)

    def test_parse_v1_fixture_meminfo_and_na_prefetch(self) -> None:
        v1_dir = self.fixtures_dir / "MOCK_S2_V1_R1"
        rec = parse_run_directory(v1_dir)
        self.assertEqual(rec["variant"], "V1")
        self.assertEqual(rec["context_source"], "real")
        # Peak PSS in meminfo.txt is max(148480, 163840) KiB = 160.0 MiB
        self.assertAlmostEqual(rec["peak_pss_mib"], 160.0)
        # V1 has 0 prefetch -> denominator 0 -> prefetch_waste_pct is None (empty in CSV, not 0)
        self.assertIsNone(rec["prefetch_waste_pct"])
        self.assertIsNone(rec["energy_mwh"])
        csv_row = record_to_csv_row(rec)
        self.assertEqual(csv_row["prefetch_waste_pct"], "")
        self.assertEqual(csv_row["energy_mwh"], "")
        self.assertEqual(csv_row["network_body_bytes"], "50140")
        self.assertEqual(csv_row["cache_hit_pct"], "0")

    def test_parse_v2_fixture_prefetch_waste_and_cache_hit(self) -> None:
        v2_dir = self.fixtures_dir / "MOCK_S2_V2_R1"
        rec = parse_run_directory(v2_dir)
        self.assertEqual(rec["variant"], "V2")
        # Prefetch keys: image 5 (12000 bytes, used) + image 6 (8000 bytes, unused)
        # Waste = 8000 / (12000 + 8000) * 100 = 40.0%
        self.assertAlmostEqual(rec["prefetch_waste_pct"], 40.0)
        # 5 successful HTTP requests, 1 served from cache -> 20.0%
        self.assertAlmostEqual(rec["cache_hit_pct"], 20.0)
        # Total network body bytes = 5800 + 13100 + 0 + 12000 + 8000 = 38900
        self.assertEqual(rec["network_body_bytes"], 38900)

    def test_parse_s6_simulated_mode_switches_vs_same_mode_policy_change(self) -> None:
        s6_dir = self.fixtures_dir / "MOCK_S6_V3_R1"
        rec = parse_run_directory(s6_dir)
        self.assertEqual(rec["scenario_id"], "S6")
        self.assertEqual(rec["context_source"], "simulated")
        # 3 policy_change events:
        # 1) BALANCED -> ECO (mode switch #1)
        # 2) ECO -> BALANCED (mode switch #2)
        # 3) BALANCED -> BALANCED (network constraint change only, NOT a mode switch)
        self.assertEqual(rec["mode_switches"], 2)
        self.assertEqual(rec["_diagnostics"]["policy_changes_same_mode"], 1)

    def test_mock_fixtures_excluded_by_default_and_aggregated_when_allowed(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            out_dir = Path(tmp)
            # Without allow_mock_fixtures, mock runs must be ignored so they never pollute real CSVs
            res_default = aggregate_to_directory(
                raw_dir=self.fixtures_dir, out_dir=out_dir, allow_mock_fixtures=False
            )
            self.assertEqual(res_default["runs_processed"], 0)

            # With allow_mock_fixtures=True, all 3 mock fixture runs are aggregated cleanly
            res_allowed = aggregate_to_directory(
                raw_dir=self.fixtures_dir, out_dir=out_dir, allow_mock_fixtures=True
            )
            self.assertEqual(res_allowed["runs_processed"], 3)
            with (out_dir / "run_metrics.csv").open("r", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(len(rows), 3)

            with (out_dir / "summary_metrics.csv").open("r", encoding="utf-8") as handle:
                summaries = list(csv.DictReader(handle))
            self.assertTrue(len(summaries) > 0)
            self.assertEqual(list(summaries[0].keys()), list(SUMMARY_COLUMNS))
            # Since n=1 per group in fixtures, iqr must be blank and insufficient_repeats must be true
            for s_row in summaries:
                self.assertEqual(s_row["n"], "1")
                self.assertEqual(s_row["iqr"], "")
                self.assertEqual(s_row["insufficient_repeats"], "true")

    def test_interleaved_schedule_and_am_start_contract(self) -> None:
        sched = build_interleaved_schedule(
            scenarios=["S2"],
            variants=["V1", "V2", "V3"],
            cache_states=["cold"],
            repeats=2,
            include_warmup=True,
        )
        warmups = [x for x in sched if x["is_warmup"]]
        measured = [x for x in sched if not x["is_warmup"]]
        self.assertEqual(len(warmups), 3)
        self.assertEqual(len(measured), 6)
        # Repeat 1 order: V1, V2, V3; Repeat 2 order: V3, V2, V1
        self.assertEqual([x["variant"] for x in measured[:3]], ["V1", "V2", "V3"])
        self.assertEqual([x["variant"] for x in measured[3:6]], ["V3", "V2", "V1"])

        cmd = build_am_start_command(
            variant="V3",
            run_id="S2_V3_cold_r01",
            scenario_id="S2",
            commit="abc1234",
            config_hash="cfg5678",
            reset_mode="cold_cache",
        )
        self.assertIn("com.adaptivenews.app/.ui.MainActivity", cmd)
        self.assertEqual(improvement_lower_is_better(0.0, 10.0), None)
        self.assertAlmostEqual(improvement_lower_is_better(100.0, 80.0) or 0.0, 20.0)
        self.assertAlmostEqual(
            parse_meminfo_peak_pss_mib("  TOTAL PSS: 102400\n") or 0.0, 100.0
        )


if __name__ == "__main__":
    unittest.main()
