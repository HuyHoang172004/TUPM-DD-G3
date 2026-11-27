# BÁO CÁO ĐO ĐẠC, KIỂM THỬ VÀ HƯỚNG DẪN TÁI LẬP — ADAPTIVE NEWS

**Người phụ trách:** Phạm Bá Tú (`android/benchmark/`, `experiments/`, `scripts/`)  
**Tham chiếu:** `Adaptive_News_CORE.md` (Mục 2, 4, 9, 10, 11, 13, 14)

---

## 1. Tổng quan các hạng mục đã triển khai

Toàn bộ hạ tầng đo đạc, kịch bản tự động hóa Macrobenchmark/UIAutomator và bộ công cụ xử lý dữ liệu thực nghiệm thuộc phạm vi của Tú đã được hoàn thiện và kiểm thử độc lập:

1. **Module Macrobenchmark (`android/benchmark/`):**
   - `BenchmarkConfig.kt`: Định nghĩa khóa hợp đồng tích hợp (`com.adaptivenews.app`, `MainActivity`, hệ thống test tags cố định Mục 9, từ khóa fixture `"công nghệ 00001"` và `article_1`, quy tắc kiểm tra Intent extras `[A-Za-z0-9_-]{1,64}`).
   - `ScenarioDriver.kt`: Các hàm điều khiển UIAutomator chuẩn hóa cho toàn bộ kịch bản **S1–S8** (khởi động cold/warm, cuộn định sẵn 60 giây cho S2/S3, tìm kiếm sau debounce 300 ms và mở chi tiết cho S4, lưu bài và ngắt mạng thật đọc offline cho S5, tiêm chuỗi trạng thái giả lập `Normal → Critical → Normal → Slow → Normal` tại màn Lab cho S6, kiểm thử chịu tải 5 phút cho S7 và workload dài hạn cho S8).
   - `AdaptiveNewsMacrobenchmark.kt`: Bộ test JUnit4 tích hợp `MacrobenchmarkRule`, `StartupTimingMetric` và `FrameTimingMetric` cho cả 3 phiên bản `V1`, `V2`, `V3`.
2. **Giao thức và Schema dữ liệu (`experiments/`):**
   - `experiments/protocol.md`: Đặc tả chi tiết giao thức đo, phiếu môi trường, quy tắc lặp/xen kẽ, cách ly trạng thái cache `cold`/`warm`, và công thức toán học của 25 cột dữ liệu.
   - `experiments/processed/run_metrics.csv`: Khởi tạo đúng 25 cột chuẩn theo Mục 11.2.
   - `experiments/processed/summary_metrics.csv`: Khởi tạo bảng tổng hợp thống kê (`n`, `median`, `q1`, `q3`, `iqr`, `min`, `max`, `failed_runs`, `insufficient_repeats`).
   - `experiments/fixtures/mock_runs/`: Bộ dữ liệu fixture mẫu gắn cờ `"is_mock_fixture": true` dùng riêng để kiểm thử tự động bộ parser và aggregator (tách biệt hoàn toàn khỏi `experiments/raw/` để không làm bẩn dữ liệu thực nghiệm).
3. **Bộ Script thu thập, phân tích và tổng hợp (`scripts/`):**
   - `scripts/parse_telemetry.py`: Phân tích một thư mục run `experiments/raw/<run_id>/` (`metadata.json`, `events.jsonl`, `meminfo.txt`, `benchmark.json`) thành bản ghi 25 cột chuẩn.
   - `scripts/aggregate_metrics.py`: Tổng hợp toàn bộ các run trong `experiments/raw/` thành `run_metrics.csv`, `summary_metrics.csv` và `comparison_report.json` (tính trung vị, IQR nội suy tuyến tính tại `(n - 1) * p`, đánh giá mức cải thiện RQ1/RQ2/RQ3 và kiểm tra ngưỡng bảo vệ Guardrails).
   - `scripts/run_experiments.py`: Điều phối chạy tự động qua `adb` theo lịch xen kẽ (`V1 ↔ V2 ↔ V3`), quản lý reset cache (`cold`/`warm`), lấy mẫu `dumpsys meminfo`, kéo tệp `files/telemetry/<run_id>/events.jsonl` từ thiết bị về `experiments/raw/<run_id>/` và gọi tổng hợp tự động.
   - `scripts/test_pipeline.py`: Bộ unit test kiểm chứng toàn bộ công thức tính metric, schema JSONL/CSV, xử lý giá trị trống (`null`/N/A) và quy tắc thống kê.

---

## 2. Hướng dẫn tái lập quy trình đo đạc từ đầu đến cuối

### Bước 1: Chuẩn bị và khởi chạy máy chủ Backend (của Hoàn)

Từ thư mục gốc của repository:

```powershell
python -m venv backend/.venv
.\backend\.venv\Scripts\python.exe -m pip install -r backend/requirements.txt
.\backend\.venv\Scripts\python.exe -m backend.generate
.\backend\.venv\Scripts\python.exe -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

- Kiểm tra endpoint: `curl.exe http://127.0.0.1:8000/health` trả về `{"status":"ok"}`.
- Với trình giả lập (Emulator), ứng dụng tự động kết nối tới `http://10.0.2.2:8000/`.
- Với thiết bị vật lý thật kết nối qua USB, chạy:
  ```powershell
  adb reverse tcp:8000 tcp:8000
  ```

### Bước 2: Biên dịch và cài đặt bản dựng `benchmark` của ứng dụng

```powershell
cd android
.\gradlew.bat :app:assembleBenchmark :app:installBenchmark
```

> Lưu ý: Bản dựng `benchmark` đã được cấu hình `isProfileable = true`, `isDebuggable = false`, ký bằng debug key và cho phép cleartext HTTP nội bộ để đo hiệu năng chính xác theo khuyến nghị của Android Macrobenchmark.

### Bước 3: Kiểm tra tự động bộ công cụ Parser và Aggregator (Unit Tests)

Chạy bộ kiểm thử đơn vị của pipeline đo đạc (không cần thiết bị đang kết nối):

```powershell
python -m unittest scripts/test_pipeline.py -v
```

Hoặc chạy thử nghiệm tổng hợp trên bộ fixture mẫu (xuất ra thư mục tạm, không ghi đè dữ liệu thật):

```powershell
python scripts/aggregate_metrics.py --raw-dir experiments/fixtures/mock_runs --allow-mock-fixtures --out-dir experiments/fixtures/preview_out
```

### Bước 4: Thực thi đợt đo chính thức S1–S8 trên Thiết bị / Emulator

1. **Xem trước lịch chạy xen kẽ (Dry-run):**
   ```powershell
   python scripts/run_experiments.py --scenarios S1 S2 S3 S4 S5 S6 S7 --variants V1 V2 V3 --repeats 5 --dry-run
   ```
2. **Chạy đo tự động qua `adb` và thu thập dữ liệu thô vào `experiments/raw/`:**
   ```powershell
   python scripts/run_experiments.py --scenarios S1 S2 S4 S5 S6 S7 --variants V1 V2 V3 --repeats 5 --cache-states cold warm
   ```
3. **Chạy kịch bản mạng chậm S3 (sau khi bật network shaping 1 Mbps / 250 ms):**
   - Trên Emulator: cấu hình mạng qua консоль hoặc `adb emu network delay 250` và `adb emu network speed 1000 1000`.
   - Thực thi kịch bản S3:
     ```powershell
     python scripts/run_experiments.py --scenarios S3 --variants V1 V2 V3 --repeats 5 --cache-states cold warm --network-profile "slow_1mbps_250ms"
     ```
4. **Tổng hợp bảng chỉ số `run_metrics.csv` và `summary_metrics.csv`:**
   ```powershell
   python scripts/aggregate_metrics.py --raw-dir experiments/raw --out-dir experiments/processed
   ```

---

## 3. Trạng thái số liệu thực nghiệm và Hạn chế ghi nhận

- **Trạng thái số liệu hiện tại:** Tuân thủ nghiêm ngặt quy định của dự án (*"không tạo số liệu thực nghiệm giả"*), tệp `experiments/processed/run_metrics.csv` và `experiments/processed/summary_metrics.csv` hiện chỉ chứa cấu trúc cột chuẩn và sẵn sàng nhận dữ liệu từ đợt chạy đo trên thiết bị thật/emulator khi đóng băng commit (`freeze commit`).
- **Các hạn chế và lưu ý kỹ thuật đã kiểm soát trong pipeline:**
  1. **Tách biệt nguồn ngữ cảnh (`context_source`):** Kịch bản S6 sử dụng snapshot tiêm qua màn hình Lab (`context_source=simulated`). Bộ tổng hợp `aggregate_metrics.py` tự động tách riêng nhóm `simulated` khỏi `real` để chỉ đánh giá tính đúng đắn của logic chuyển mode (`mode_switches`) và chi phí đánh giá (`engine_p95_ms`), tuyệt đối không dùng S6 để kết luận mức giảm RAM hay tiết kiệm năng lượng thực tế.
  2. **Độ trễ lấy mẫu bộ nhớ PSS:** Chỉ số `peak_pss_mib` được lấy từ `dumpsys meminfo` theo chu kỳ cố định nên phản ánh đỉnh bộ nhớ quan sát được tại các mốc lấy mẫu mà không làm biến dạng tải CPU của ứng dụng.
  3. **Ranh giới đo lưu lượng mạng (`network_body_bytes`):** Chỉ số này phản ánh chính xác số byte phần thân phản hồi (response body bytes) đọc thực tế từ socket mạng qua network interceptor của OkHttp, không bao gồm kích thước HTTP header, bắt tay TLS hoặc dữ liệu phục vụ từ cache cục bộ.
