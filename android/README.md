# Adaptive News Android — phần UI và tích hợp dữ liệu

## Kế hoạch triển khai

1. Khóa skeleton Gradle, package và `core/contract` đúng CORE, dùng fixture để UI chạy độc lập.
2. Xây Feed, Search, Detail, Saved và Lab; giữ test tag ổn định cho Tú.
3. Thu thập snapshot thiết bị, áp dụng engine V1/V2/V3, nối policy với UI và Coil.
4. Ghi telemetry cùng schema cho mọi variant; kiểm tra engine bằng test vector và build benchmark.
5. Kết nối `NewsData` của Hoàn với UI, Paging 3, Coil, prefetch và telemetry.

## Cấu hình đã chốt

Gradle 8.13, Android Gradle Plugin 8.11.1, Kotlin 2.0.21, Compose BOM 2024.10.01, Coil 3.0.4, minSdk 26, compileSdk 36, targetSdk 35, Java 17. Các phiên bản này được ghim trong `gradle/libs.versions.toml`; giữ cố định trong một đợt đo. `debug` và `benchmark` cho phép HTTP cleartext để nối backend demo nội bộ; `release` không cho phép. Build `benchmark` không debuggable, có `profileable`, ký debug để cài thử.

Mở thư mục `android/` trong Android Studio. Có thể chạy `gradlew.bat :app:assembleDebug`, `gradlew.bat :app:assembleBenchmark` và `gradlew.bat :app:testDebugUnitTest` sau khi thiết lập Android SDK/JDK. Package là `com.adaptivenews.app`.

## Chạy với backend

App hiện dùng `NewsData` và `RemoteNewsRepository` thật. Trước khi mở app, cài dependency Python, sinh dataset và chạy API theo [hướng dẫn backend](../backend/README.md). Emulator truy cập `http://10.0.2.2:8000/`; thiết bị thật cần `adb reverse tcp:8000 tcp:8000` và dùng `http://127.0.0.1:8000/`. Nếu API chưa chạy, Feed sẽ hiện lỗi kết nối.

`RepositoryBinding` giữ một `NewsData` cho mỗi process, nối policy hiện hành, telemetry và tín hiệu latency bài viết. V1 gọi `page()` liên tiếp để tải summary; V2/V3 dùng `ArticleFeed.paged` với Paging 3. Coil sử dụng `NewsData.imageCalls`, cùng HTTP cache và bộ giới hạn request với data layer. UI cấp các bài ngay sau viewport cho `ImagePrefetcher` và báo lại URL ảnh đã hiển thị. Room lưu bài đã chọn để đọc offline; fixture 30 bài vẫn ở source/assets để kiểm tra độc lập và không dùng cho kết luận hiệu năng.

Ảnh dùng URL từ `NewsData.imageUrls` để Coil và prefetch dùng cùng khóa cache. Coil giữ một loader đang hoạt động và chỉ đổi khi budget cache hoặc HTTP client đổi. Giá trị cache trong policy là budget mục tiêu, không phải số PSS thực đo.

## Lab và đo

Lab chọn V1/V2/V3 trước một run; app khởi động lại khi đổi variant. Có nút reset cache ảnh (Coil + HTTP), reset dữ liệu Room riêng, inject Normal/Critical/Slow và trở về trạng thái thật. Reset dữ liệu khởi động lại Activity và xóa cả bài đã lưu. Snapshot giả lập được lặp mỗi 5 giây để thấy hysteresis/cooldown; telemetry ghi `source=simulated`. Build benchmark nhận intent extra `variant`, `run_id`, `scenario_id`; nếu cung cấp một trong ba thì phải cung cấp cả ba hợp lệ. Có thể truyền thêm `commit`, `config_hash`, `reset_mode` để điền metadata run; trường không có được giữ `null`. Chuỗi ID chỉ nhận chữ, số, `_`, `-` để giữ đường dẫn export an toàn.

Telemetry lưu `files/telemetry/<run_id>/events.jsonl`, UTF-8, flush theo batch hoặc sau 5 giây và xuất bằng nút Lab qua Storage Access Framework. `DataTelemetry` chuyển `request_end`, `prefetch_end` và `prefetch_used` vào cùng log. Tú cấp commit/config hash thực và metadata run khi đo chính thức; các trường chưa có được ghi `null`, không tạo số liệu giả. Không dùng snapshot giả lập để suy ra RAM/pin/nhiệt thực tế.

## Giới hạn hiện tại

WorkManager dùng `NewsApplication` và worker factory của data layer, giữ một lịch `news-feed-sync` với chu kỳ 15/30/60 phút theo policy. Lần đồng bộ đầu được hẹn sau một chu kỳ để tránh ảnh hưởng lúc mở app. Worker có thể tạo lại data graph khi hệ thống khởi động process mà chưa có Activity. Nếu API tạm ngắt giữa lúc đọc một GET, repository thử lại tối đa một lần; lỗi HTTP 4xx/5xx không bị thử lại theo đường này. Ảnh offline chỉ có khi ảnh tương ứng đã vào cache. Đã kiểm tra chức năng trên emulator với dataset 10.000 bài, nhưng chưa có số đo thực nghiệm S1–S8 hoặc smoke test trên thiết bị thật; module benchmark, script và số liệu đo thuộc Tú. Báo cáo thuộc Chiến.
