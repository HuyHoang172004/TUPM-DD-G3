import json
import tempfile
import unittest
from pathlib import Path

from lab.analysis import benchmark_metrics, bootstrap_difference, csv_report, metrics_from_events, percentile, summarize
from lab.catalog import METHODS, METRICS, configuration


def event(name, at, value=None, **attrs):
    return dict(event=name, elapsed_ms=at, value=value, attrs=attrs)


class AnalysisTests(unittest.TestCase):
    def test_missing_data_and_zero_are_distinct(self):
        self.assertIsNone(percentile([], 95))
        self.assertEqual(percentile([0], 95), 0)
        self.assertEqual(percentile([1, 3], 50), 2)

    def test_measurement_window_excludes_setup_and_teardown(self):
        rows = [event("request_end", 0, bytes_body=999), event("measure_start", 10),
                event("request_end", 20, bytes_body=42, status=200, cache_source="network"),
                event("measure_end", 30), event("request_end", 40, bytes_body=999)]
        metrics = metrics_from_events(rows)
        self.assertEqual(metrics["network_body_bytes"], 42)
        self.assertIsNone(metrics["energy_mwh"] if "energy_mwh" in metrics else None)

    def test_missing_markers_reject_trial(self):
        with self.assertRaises(ValueError):
            metrics_from_events([event("request_end", 1, bytes_body=42)])

    def test_conditional_cache_does_not_invent_network_body(self):
        rows = [event("measure_start", 0), event("request_end", 1, bytes_body=0, status=200, cache_source="conditional_cache"), event("measure_end", 2)]
        result = metrics_from_events(rows, proxy=[dict(status=304)])
        self.assertEqual(result["network_body_bytes"], 0)
        self.assertEqual(result["conditional_requests"], 1)
        self.assertEqual(result["http_hit_pct"], 100)

    def test_prefetch_is_accounted_by_successful_transferred_bytes(self):
        rows = [event("measure_start", 0), event("prefetch_end", 1, 20, key="used", success=True),
                event("prefetch_end", 2, 80, key="wasted", success=True),
                event("prefetch_end", 3, 1000, key="failed", success=False),
                event("prefetch_used", 4, key="used"), event("measure_end", 5)]
        result = metrics_from_events(rows)
        self.assertEqual(result["prefetch_waste_pct"], 80)
        self.assertEqual(result["prefetch_use_pct"], 50)

    def test_pss_is_not_java_heap(self):
        result = metrics_from_events([event("measure_start", 0), event("measure_end", 2)], pss=[dict(pss_kib=102400)])
        self.assertEqual(result["peak_pss_mib"], 100)

    def test_mode_switches_do_not_count_same_mode_policy_changes(self):
        rows = [event("measure_start", 0), event("engine_eval", 1, .1),
                dict(event("policy_change", 2, old_mode="BALANCED"), mode="BALANCED"),
                dict(event("policy_change", 3, old_mode="BALANCED"), mode="ECO"), event("measure_end", 4)]
        self.assertEqual(metrics_from_events(rows)["mode_switches"], 1)

    def test_pairs_exclude_failures_and_separate_memory_phase(self):
        trials = [dict(repeat=1, arm="A", phase="performance", status="completed", metrics=dict(peak_pss_mib=999)),
                  dict(repeat=1, arm="A", phase="memory", status="completed", metrics=dict(peak_pss_mib=20)),
                  dict(repeat=1, arm="B", phase="memory", status="failed", metrics=dict(peak_pss_mib=1))]
        summary = summarize(trials, ["peak_pss_mib"])["peak_pss_mib"]
        self.assertIsNone(summary["A"]["median"])
        self.assertEqual(trials[1]["metrics"]["peak_pss_mib"], 20)
        self.assertEqual(summary["pairs"], 0)
        self.assertEqual(summary["verdict"], "not_measured")

    def test_bootstrap_requires_five_pairs_and_is_reproducible(self):
        self.assertIsNone(bootstrap_difference([(1, 0)]*4))
        self.assertEqual(bootstrap_difference([(10, 5)]*5), [-5, -5])

    def test_direction_and_zero_baseline(self):
        trials = [dict(repeat=i, arm=arm, phase="performance", status="completed", metrics={"memory_hit_pct": value, "requests": 0}) for i in range(10) for arm, value in [("A", 20), ("B", 80)]]
        summary = summarize(trials, ["memory_hit_pct", "requests"])
        self.assertEqual(summary["memory_hit_pct"]["verdict"], "improved")
        self.assertIsNone(summary["requests"]["improvement_pct"])

    def test_benchmark_json_percentile_uses_raw_frame_samples(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            (path/"test-benchmarkData.json").write_text(json.dumps({"benchmarks": [{"sampledMetrics": {"frameDurationCpuMs": {"runs": [[1, 2, 100]]}}, "metrics": {"timeToInitialDisplayMs": {"runs": [500]}}}]}))
            result = benchmark_metrics(path)
            self.assertEqual(result["startup_ms"], 500)
            self.assertAlmostEqual(result["frame_p95_ms"], 90.2)

    def test_workmanager_interval_comes_from_checked_work_not_requested_config(self):
        rows = [event("experiment_config", 0, method_id="workmanager", worker_minutes=60),
                event("measure_start", 1),
                event("schedule_checked", 2, interval_ms=900_000, unique_work_count=1),
                event("measure_end", 3)]
        result = metrics_from_events(rows)
        self.assertEqual(result["scheduled_interval_minutes"], 15)
        self.assertEqual(result["unique_active_work"], 1)
        self.assertEqual(result["worker_runs"], 0)

    def test_search_compares_only_same_final_query_after_last_keystroke(self):
        rows = [event("measure_start", 0), event("query_input", 1, text="0"),
                event("search_ready", 50, 49, query="0"),
                event("query_input", 100, text="00001"),
                event("search_ready", 110, 10, query="0"),
                event("search_ready", 500, 400, query="00001"), event("measure_end", 600)]
        self.assertEqual(metrics_from_events(rows)["final_search_ms"], 400)
        self.assertIsNone(metrics_from_events(rows[:4] + [event("measure_end", 600)])["final_search_ms"])

    def test_all_catalog_metrics_exist_and_configs_are_independent(self):
        for method in METHODS:
            for metric in method["metrics"]:
                self.assertIn(metric, METRICS)
            for case in method["cases"]:
                a = configuration(method["id"], case["id"], "A")
                b = configuration(method["id"], case["id"], "B")
                allowed = set(case["a"]) | set(case["b"])
                self.assertTrue({k for k in a if a[k] != b[k]}.issubset(allowed))
        with self.assertRaises(ValueError):
            configuration("lazy_init", "default", "A")

    def test_csv_preserves_unmeasured_values_as_empty(self):
        result = csv_report(dict(id="R1", trials=[dict(trial_id="T1", repeat=1, arm="A", phase="performance", status="failed", metrics={})]))
        self.assertIn("R1,T1,1,A,performance,failed", result)
        self.assertNotIn("None", result)

    def test_injected_engine_sequence_excludes_startup_and_measures_recovery(self):
        rows = [event("experiment_config", 0, method_id="hysteresis"), event("measure_start", 1),
                dict(event("policy_change", 2, old_mode="BALANCED"), mode="PERFORMANCE", source="real"),
                dict(event("resource_snapshot", 10, memory="CRITICAL"), source="simulated"),
                dict(event("engine_eval", 10, .05), source="simulated"),
                dict(event("policy_change", 11, old_mode="PERFORMANCE"), mode="ECO", source="simulated"),
                dict(event("resource_snapshot", 20, memory="NORMAL"), source="simulated"),
                dict(event("policy_change", 30020, old_mode="ECO"), mode="PERFORMANCE", source="simulated"),
                event("composition_count", 40000, 100), event("measure_end", 40001)]
        result = metrics_from_events(rows)
        self.assertEqual(result["mode_switches"], 2)
        self.assertEqual(result["critical_reaction_ms"], 1)
        self.assertEqual(result["recovery_ms"], 30000)
        self.assertEqual(result["recompositions"], 100)


if __name__ == "__main__":
    unittest.main()
