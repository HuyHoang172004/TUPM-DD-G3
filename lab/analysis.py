"""Deterministic, missing-aware analysis. No generated performance observations."""
import csv
import html
import io
import json
import math
import random
from collections import defaultdict
from statistics import median

from .catalog import METRICS


def percentile(values, p):
    values = sorted(x for x in values if isinstance(x, (int, float)) and math.isfinite(x))
    if not values:
        return None
    position = (len(values) - 1) * p / 100
    low, high = math.floor(position), math.ceil(position)
    return values[low] + (values[high] - values[low]) * (position - low)


def read_jsonl(path):
    if not path.exists():
        return []
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def metrics_from_events(events, pss=(), proxy=()):
    starts = [e["elapsed_ms"] for e in events if e.get("event") == "measure_start"]
    ends = [e["elapsed_ms"] for e in events if e.get("event") == "measure_end"]
    if not starts or not ends:
        raise ValueError("Thiếu mốc measure_start/measure_end; không tính metric ngoài khoảng đo")
    start, end = starts[-1], ends[-1]
    if end < start:
        raise ValueError("Khoảng đo không hợp lệ")
    measured = [e for e in events if start <= e.get("elapsed_ms", -1) <= end]
    buckets = defaultdict(list)
    for e in measured:
        buckets[e.get("event")].append(e)
    configs = [e.get("attrs", {}) for e in events if e.get("event") == "experiment_config"]
    config = configs[-1] if configs else {}
    if config.get("method_id") == "hysteresis" or config.get("method_id") == "adaptive" and config.get("case_id") == "injected":
        for name in ("engine_eval", "policy_change"):
            buckets[name] = [e for e in buckets[name] if e.get("source") == "simulated"]
    result = {k: None for k in METRICS}
    for event, key in [("image_visible", "image_p95_ms"),
                       ("detail_ready", "detail_p95_ms"), ("engine_eval", "engine_p95_ms"),
                       ("room_query", "query_p95_ms")]:
        result[key] = percentile([e.get("value") for e in buckets[event]], 95)
    if buckets["query_input"]:
        last_input = buckets["query_input"][-1]
        final_query = last_input.get("attrs", {}).get("text", "").strip()
        final_ready = [e for e in buckets["search_ready"]
                       if e["elapsed_ms"] >= last_input["elapsed_ms"]
                       and e.get("attrs", {}).get("query") == final_query]
        if final_ready:
            result["final_search_ms"] = final_ready[0].get("value")
    ready = [e.get("value") for e in buckets["feed_ready"] if isinstance(e.get("value"), (int, float))]
    result["feed_ready_ms"] = ready[0] if ready else None
    requests = buckets["request_end"]
    detail_requests = [e for e in requests if e.get("attrs", {}).get("url_path") == "/v1/articles/9999"]
    if detail_requests:
        result["dedup_body_bytes"] = sum(e["attrs"].get("bytes_body", 0) for e in detail_requests)
        result["dedup_network_requests"] = sum(e.get("path") == "/v1/articles/9999" for e in proxy)
    if requests:
        result["network_body_bytes"] = sum(e["attrs"].get("bytes_body", 0) for e in requests)
        result["errors"] = sum(e["attrs"].get("status") == "io_error" or
                               isinstance(e["attrs"].get("status"), int) and e["attrs"]["status"] >= 400 for e in requests)
        result["cancelled_requests"] = sum(e["attrs"].get("status") == "cancelled" for e in requests)
        result["http_hit_pct"] = 100 * sum(e["attrs"].get("cache_source") in ("cache", "conditional_cache") for e in requests) / len(requests)
    if proxy is not None:
        result["requests"] = len(proxy)
        result["conditional_requests"] = sum(e.get("status") == 304 for e in proxy)
    images = buckets["image_visible"]
    if images:
        result["memory_hit_pct"] = 100 * sum(e.get("attrs", {}).get("cache_source") == "MEMORY_CACHE" for e in images) / len(images)
    if buckets["item_composed"]:
        result["recompositions"] = len(buckets["item_composed"])
    if buckets["composition_count"]:
        result["recompositions"] = buckets["composition_count"][-1].get("value")
    changes = buckets["policy_change"]
    snapshots = buckets["resource_snapshot"]
    critical = next((e for e in snapshots if e.get("source") == "simulated" and e.get("attrs", {}).get("memory") == "CRITICAL"), None)
    if critical:
        eco = next((e for e in changes if e["elapsed_ms"] >= critical["elapsed_ms"] and e.get("mode") == "ECO"), None)
        if eco:
            result["critical_reaction_ms"] = eco["elapsed_ms"]-critical["elapsed_ms"]
            recovered = next((e for e in snapshots if e["elapsed_ms"] > eco["elapsed_ms"] and e.get("source") == "simulated" and e.get("attrs", {}).get("memory") == "NORMAL"), None)
            performance = next((e for e in changes if recovered and e["elapsed_ms"] >= recovered["elapsed_ms"] and e.get("mode") == "PERFORMANCE"), None)
            if recovered and performance:
                result["recovery_ms"] = performance["elapsed_ms"]-recovered["elapsed_ms"]
    if buckets["engine_eval"]:
        result["mode_switches"] = sum(e.get("attrs", {}).get("old_mode") != e.get("mode") for e in changes)
    active = [e.get("attrs", {}).get("active") for e in buckets["request_started"]]
    result["max_active_requests"] = max((x for x in active if isinstance(x, int)), default=None)
    cpu = [(e["elapsed_ms"], e.get("attrs", {}).get("app_cpu_pct")) for e in buckets["resource_snapshot"]]
    cpu = [(t, x) for t, x in cpu if isinstance(x, (int, float))]
    if len(cpu) > 1:
        weighted = [(cpu[i][0] - cpu[i-1][0], cpu[i][1]) for i in range(1, len(cpu)) if cpu[i][0] > cpu[i-1][0]]
        if weighted:
            result["cpu_pct"] = sum(dt*x for dt, x in weighted) / sum(dt for dt, _ in weighted)
    if pss:
        result["peak_pss_mib"] = max(x["pss_kib"] for x in pss) / 1024
    prefetch = [e for e in buckets["prefetch_end"] if e.get("attrs", {}).get("success")]
    used = {e.get("attrs", {}).get("key") for e in buckets["prefetch_used"]}
    # Accounting is by unique cache key. Only successful transferred body bytes count.
    transferred = defaultdict(int)
    for e in prefetch:
        transferred[e.get("attrs", {}).get("key")] += max(0, e.get("value") or 0)
    if prefetch:
        keys = set(transferred)
        result["prefetch_use_pct"] = 100 * len(keys & used) / len(keys)
        total = sum(transferred.values())
        if total:
            result["prefetch_waste_pct"] = 100 * sum(v for k, v in transferred.items() if k not in used) / total
    completed = buckets["consumer_complete"]
    if completed:
        result["consumer_successes"] = sum(e.get("attrs", {}).get("success") is True for e in completed)
    if buckets["offline_result"]:
        result["offline_success"] = int(all(e.get("attrs", {}).get("success") for e in buckets["offline_result"]))
    if buckets["worker_end"] or any(e.get("event") == "experiment_config" and e.get("attrs", {}).get("method_id") == "workmanager" for e in events):
        result["worker_runs"] = len(buckets["worker_end"])
    if buckets["schedule_checked"]:
        schedule = buckets["schedule_checked"][-1].get("attrs", {})
        interval = schedule.get("interval_ms")
        result["scheduled_interval_minutes"] = interval/60_000 if isinstance(interval, (int, float)) else None
        result["unique_active_work"] = schedule.get("unique_work_count")
    return result


def benchmark_metrics(directory):
    result = {}
    for path in directory.rglob("*benchmarkData.json"):
        data = json.loads(path.read_text(encoding="utf-8"))
        for bench in data.get("benchmarks", []):
            for source, target, p in [("timeToInitialDisplayMs", "startup_ms", 50),
                                       ("frameDurationCpuMs", "frame_p95_ms", 95),
                                       ("frameOverrunMs", "frame_overrun_p95_ms", 95)]:
                metric = bench.get("metrics", {}).get(source) or bench.get("sampledMetrics", {}).get(source)
                if not metric:
                    continue
                samples = metric.get("runs", [])
                flat = [v for row in samples for v in (row if isinstance(row, list) else [row])]
                value = percentile(flat, p)
                if value is not None:
                    result[target] = value
    return result


def bootstrap_difference(pairs):
    if len(pairs) < 5:
        return None
    rng = random.Random(42)
    diffs = [b-a for a, b in pairs]
    samples = [median(rng.choices(diffs, k=len(diffs))) for _ in range(2000)]
    return [percentile(samples, 2.5), percentile(samples, 97.5)]


def summarize(trials, metric_ids):
    output = {}
    # Memory and frame metrics come from separate, explicitly named phases.
    for metric in metric_ids:
        phase = "memory" if metric == "peak_pss_mib" else "performance"
        valid = [t for t in trials if t.get("status") == "completed" and t["phase"] == phase]
        paired = defaultdict(dict)
        for t in valid:
            value = t.get("metrics", {}).get(metric)
            if value is not None:
                paired[t["repeat"]][t["arm"]] = value
        pairs = [(x["A"], x["B"]) for x in paired.values() if "A" in x and "B" in x]
        values = {"A": [a for a, _ in pairs], "B": [b for _, b in pairs]}
        arms = {arm: {"n": len(v), "median": percentile(v, 50), "q1": percentile(v, 25), "q3": percentile(v, 75)} for arm, v in values.items()}
        ci = bootstrap_difference(pairs)
        a, b = arms["A"]["median"], arms["B"]["median"]
        direction = METRICS[metric][2]
        improvement = None if a in (None, 0) or b is None or direction == "neutral" else (a-b)/a*100 * (1 if direction == "lower" else -1)
        verdict = "not_measured" if a is None or b is None else "insufficient"
        if ci and direction != "neutral":
            sign = 1 if direction == "lower" else -1
            if ci[1]*sign < 0 and sign == 1 or ci[0]*sign < 0 and sign == -1:
                verdict = "improved"
            elif ci[0]*sign > 0 and sign == 1 or ci[1]*sign > 0 and sign == -1:
                verdict = "regressed"
        output[metric] = dict(**arms, pairs=len(pairs), difference=None if a is None or b is None else b-a,
                              improvement_pct=improvement, ci95_difference=ci, verdict=verdict)
    return output


def csv_report(run):
    stream = io.StringIO(newline="")
    fields = ["run_id", "trial_id", "repeat", "arm", "phase", "status", "config_hash", *METRICS]
    writer = csv.DictWriter(stream, fieldnames=fields)
    writer.writeheader()
    for trial in run["trials"]:
        writer.writerow({"run_id": run["id"], **{k: trial.get(k) for k in fields if k != "run_id" and k not in METRICS}, **trial.get("metrics", {})})
    return stream.getvalue()


def html_report(run, method):
    escape = lambda x: html.escape(str(x))
    rows = []
    for key, value in run.get("summary", {}).items():
        label, unit, _ = METRICS[key]
        show = lambda x: "N/A" if x is None else f"{x:,.3f}"
        ci = value.get("ci95_difference")
        interval = "N/A" if ci is None else f"[{show(ci[0])}; {show(ci[1])}]"
        verdict = {"not_measured": "Chưa đo", "insufficient": "Chưa đủ bằng chứng", "improved": "Cải thiện rõ", "regressed": "Suy giảm / đánh đổi"}[value["verdict"]]
        arms = {arm: f"{show(value[arm]['median'])} [{show(value[arm]['q1'])}; {show(value[arm]['q3'])}]" for arm in ("A", "B")}
        rows.append(f"<tr><td>{escape(label)} ({unit})</td><td>{arms['A']}</td><td>{arms['B']}</td><td>{show(value['improvement_pct'])}</td><td>{value['pairs']}</td><td>{interval}</td><td>{verdict}</td></tr>")
    return f"""<!doctype html><html lang="vi"><meta charset="utf-8"><title>Report {escape(run['id'])}</title>
<style>body{{font:16px system-ui;max-width:1100px;margin:40px auto;padding:20px;color:#13243b}}table{{border-collapse:collapse;width:100%}}td,th{{border:1px solid #ccc;padding:12px;text-align:left}}pre{{white-space:pre-wrap}}@media print{{button{{display:none}}}}</style>
<h1>{escape(method['title'])}</h1><p>{escape(run['id'])} · {escape(run['mode'])} · {escape(run['status'])}</p>
<p>Android emulator. Body bytes không gồm header/TLS. PSS đo ở phase riêng. Video ghi trên host. Năng lượng: không đo.</p>
<p>{escape(method['limitation'])}</p><table><tr><th>Chỉ số</th><th>A median [Q1; Q3]</th><th>B median [Q1; Q3]</th><th>Cải thiện %</th><th>n cặp</th><th>CI95 Δ(B−A)</th><th>Kết luận</th></tr>{''.join(rows)}</table>
<h2>Metadata & cấu hình</h2><pre>{escape(json.dumps(run.get('metadata', {}), ensure_ascii=False, indent=2))}</pre>
<h2>Lượt chạy</h2><pre>{escape(json.dumps(run['trials'], ensure_ascii=False, indent=2))}</pre>
<p>Khoảng tin cậy bootstrap 95% theo cặp, seed 42; dưới 5 cặp không kết luận khác biệt rõ. Raw và SHA-256 nằm trong evidence manifest.</p></html>"""
