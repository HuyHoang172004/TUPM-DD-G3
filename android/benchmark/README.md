# Adaptive News — Module Macrobenchmark (`android/benchmark`)

**Chủ sở hữu:** Phạm Bá Tú (`android/benchmark/`, `experiments/`, `scripts/`)

Module này chứa các bài đo **AndroidX Macrobenchmark** và trình điều khiển **UIAutomator** cho các kịch bản **S1–S7** trên cả 3 phiên bản `V1`, `V2`, `V3` của ứng dụng `com.adaptivenews.app`.

---

## 1. Cấu trúc mã nguồn

- `build.gradle.kts`: Cấu hình plugin `com.android.test` nhắm tới `:app` trên buildType `benchmark` (`isProfileable = true`, `isDebuggable = false`).
- `src/main/java/com/adaptivenews/benchmark/BenchmarkConfig.kt`: Định nghĩa hằng số package, `MainActivity`, toàn bộ test tags cố định theo Mục 9 của `CORE.md` (`feed_list`, `article_1`, `search_input`, `search_results`, `detail_body`, `save_button`, `saved_list`, `lab_screen`, `variant_selector`, `mode_label`, `export_button`), từ khóa fixture `"công nghệ 00001"` và hàm tạo Intent khởi chạy hợp lệ theo regex `[A-Za-z0-9_-]{1,64}`.
- `src/main/java/com/adaptivenews/benchmark/ScenarioDriver.kt`: Kịch bản thao tác UIAutomator chuẩn hóa cho S1 (Startup), S2/S3 (Cuộn feed 60 giây), S4 (Tìm kiếm + mở chi tiết), S5 (Lưu bài + ngắt mạng thật + đọc bài đã lưu offline), S6 (Tiêm chuỗi trạng thái `Normal → Critical → Normal → Slow → Normal` trên màn Lab), S7 (Chịu tải 5 phút).
- `src/main/java/com/adaptivenews/benchmark/AdaptiveNewsMacrobenchmark.kt`: Các bài kiểm thử `@RunWith(AndroidJUnit4::class)` thu thập `StartupTimingMetric` và `FrameTimingMetric`.

---

## 2. Cách kích hoạt và chạy Macrobenchmark

1. Đảm bảo trong `android/settings.gradle.kts` đã khai báo module `:benchmark` khi chạy test:
   ```kotlin
   include(":app", ":benchmark")
   ```
   *(Lưu ý: `settings.gradle.kts` là tệp cấu hình dùng chung do Hoàng khởi tạo; hoặc bạn có thể chạy trực tiếp qua `scripts/run_experiments.py` sử dụng `adb` + `:app:installBenchmark` mà không cần sửa `settings.gradle.kts`).*

2. Chạy toàn bộ bộ Macrobenchmark hoặc từng kịch bản cụ thể:
   ```powershell
   cd android
   .\gradlew.bat :benchmark:connectedBenchmarkAndroidTest `
     -Pandroid.testInstrumentationRunnerArguments.class=com.adaptivenews.benchmark.AdaptiveNewsMacrobenchmark#s2_feedScrollingFast_v3 `
     -Pandroid.testInstrumentationRunnerArguments.iterations=5 `
     -Pandroid.testInstrumentationRunnerArguments.cache_state=cold
   ```

3. Sau khi lượt chạy hoàn tất, sử dụng `scripts/run_experiments.py` hoặc `scripts/parse_telemetry.py` và `scripts/aggregate_metrics.py` để kéo tệp `files/telemetry/<run_id>/events.jsonl` cùng kết quả `benchmark.json` và `dumpsys meminfo` về `experiments/raw/<run_id>/` và xuất bảng `experiments/processed/run_metrics.csv`.
