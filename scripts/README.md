# Adaptive News — Bộ Script Đo Đạc, Phân Tích & Tổng Hợp (`scripts/`)

**Chủ sở hữu:** Phạm Bá Tú (`android/benchmark/`, `experiments/`, `scripts/`)

Thư mục này cung cấp toàn bộ pipeline điều phối thực nghiệm S1–S8, bộ parser JSONL/meminfo/Macrobenchmark và bộ tổng hợp thống kê theo đúng đặc tả tại `Adaptive_News_CORE.md` (Mục 9, 10, 11).

---

## 1. Danh mục Script

| Tệp | Chức năng |
|---|---|
| `parse_telemetry.py` | Đọc một thư mục lượt chạy `experiments/raw/<run_id>/` (`metadata.json`, `events.jsonl`, `meminfo.txt`, `gfxinfo.txt`, `benchmark.json`), kiểm tra schema JSONL (`schema_version=1`) và trích xuất bản ghi chuẩn 25 cột. |
| `aggregate_metrics.py` | Quét toàn bộ `experiments/raw/`, lọc bỏ lượt chạy `warmup` và `is_mock_fixture`, xuất `experiments/processed/run_metrics.csv` (25 cột), `summary_metrics.csv` (trung vị, Q1, Q3, IQR nội suy tuyến tính tại `(n - 1) * p`) và `comparison_report.json` (RQ1, RQ2, Guardrails). |
| `run_experiments.py` | Lập lịch chạy xen kẽ (`V1 → V2 → V3` ở lượt lẻ, `V3 → V2 → V1` ở lượt chẵn), quản lý trạng thái `cold`/`warm` cache qua `adb`, truyền Intent extras (`variant`, `run_id`, `scenario_id`, `commit`, `config_hash`, `reset_mode`), thu thập `dumpsys meminfo`, `gfxinfo` và `events.jsonl`. |
| `test_pipeline.py` | Bộ kiểm thử đơn vị (`unittest`) kiểm chứng toàn bộ công thức tính toán (`peak_pss_mib`, `prefetch_waste_pct`, `cache_hit_pct`, `mode_switches`, `linear_percentile`, `IQR`) trên bộ fixture mẫu `experiments/fixtures/mock_runs/`. |

---

## 2. Lệnh sử dụng nhanh

### Chạy kiểm thử đơn vị (Unit Tests)
```powershell
python -m unittest scripts/test_pipeline.py -v
```

### Xem trước lịch chạy xen kẽ và lệnh `adb` (Dry-Run)
```powershell
python scripts/run_experiments.py --scenarios S1 S2 S3 S4 S5 S6 S7 --variants V1 V2 V3 --repeats 5 --dry-run
```

### Phân tích một lượt chạy đơn lẻ
```powershell
python scripts/parse_telemetry.py experiments/fixtures/mock_runs/MOCK_S2_V2_R1
```

### Tổng hợp dữ liệu thực nghiệm từ `experiments/raw` sang `experiments/processed`
```powershell
python scripts/aggregate_metrics.py --raw-dir experiments/raw --out-dir experiments/processed
```
