# Adaptive News Android — phạm vi Hoàng

## Kế hoạch triển khai

1. Khóa skeleton Gradle, package và `core/contract` đúng CORE, dùng fixture để UI chạy độc lập.
2. Xây Feed, Search, Detail, Saved và Lab; giữ test tag ổn định cho Tú.
3. Thu thập snapshot thiết bị, áp dụng engine V1/V2/V3, nối policy với UI và Coil.
4. Ghi telemetry cùng schema cho mọi variant; kiểm tra engine bằng test vector và build benchmark.
5. Bàn giao điểm nối `NewsRepository`/`PolicyProvider` để Hoàn tích hợp tầng dữ liệu thật.

## Cấu hình đã chốt

Gradle 8.13, Android Gradle Plugin 8.11.1, Kotlin 2.0.21, Compose BOM 2024.10.01, Coil 3.0.4, minSdk 26, compileSdk 36, targetSdk 35, Java 17. Các phiên bản này được ghim trong `gradle/libs.versions.toml`; giữ cố định trong một đợt đo. `debug` và `benchmark` cho phép HTTP cleartext để nối backend demo nội bộ; `release` không cho phép. Build `benchmark` không debuggable, có `profileable`, ký debug để cài thử.

Mở thư mục `android/` trong Android Studio. Có thể chạy `gradlew.bat :app:assembleDebug`, `gradlew.bat :app:assembleBenchmark` và `gradlew.bat :app:testDebugUnitTest` sau khi thiết lập Android SDK/JDK. Package là `com.adaptivenews.app`.

## Fixture và tích hợp

Mặc định app dùng `FakeNewsRepository` với 30 bài, tìm kiếm không phân biệt hoa thường, trạng thái rỗng và truy vấn `__error__` để xem lỗi UI. Lưu bài fixture dùng SharedPreferences để thử luồng offline. Đây **không phải** dataset 10.000 bài và không dùng cho kết luận hiệu năng. `run_start` ghi `dataset=ui_fixture_30`; event tìm kiếm/chi tiết từ fixture ghi `source=fixture`.

Hoàn gắn repository thật qua `RepositoryBinding.factory(context, policyProvider, articleRequestSignal)`, đặt `isFixture=false` và cung cấp `resetData`. Interface `NewsRepository` và `PolicyProvider` nằm trong `core/contract/Contracts.kt` theo CORE. UI gọi `page()` để chạy độc lập; adapter Paging 3, cache HTTP, prefetch, đồng bộ nền và lưu bài thật thuộc `data/` do Hoàn sở hữu. Khi nối tầng dữ liệu thật, cần dùng cùng `ImageLoaderHolder.sharedClient` hoặc một pool chung tương đương để giới hạn tổng request Coil + data; đồng thời gọi `articleRequestSignal.successfulRequest(durationMs)` cho request bài viết thành công. Không lấy thời gian decode ảnh làm tín hiệu mạng. Trang feed không tự refresh khi policy đổi page size; page size mới áp dụng cho truy vấn hoặc refresh kế tiếp.

Ảnh dùng URL tương đối từ API; app ghép `http://10.0.2.2:8000` trên emulator và `http://127.0.0.1:8000` trên máy thật khi đã chạy `adb reverse tcp:8000 tcp:8000`. Coil dùng một loader đang hoạt động, đổi loader khi budget cache đổi theo mode và giới hạn request ảnh bằng OkHttp dispatcher. Giá trị cache trong policy là budget mục tiêu, không phải số PSS thực đo.

## Lab và đo

Lab chọn V1/V2/V3 trước một run; app khởi động lại khi đổi variant. Có nút reset cache ảnh riêng, reset dữ liệu riêng, inject Normal/Critical/Slow và trở về trạng thái thật. Snapshot giả lập được lặp mỗi 5 giây để thấy hysteresis/cooldown; telemetry ghi `source=simulated`. Build benchmark nhận intent extra `variant`, `run_id`, `scenario_id`. Chuỗi ID chỉ nhận chữ, số, `_`, `-` để giữ đường dẫn export an toàn.

Telemetry lưu `files/telemetry/<run_id>/events.jsonl`, UTF-8, flush theo batch hoặc sau 5 giây và xuất bằng nút Lab qua Storage Access Framework. Khi repository thật được nối, Hoàn phát thêm `request_end`, `prefetch_end` và `prefetch_used` theo schema CORE. Tú cấp commit/config hash thực và metadata run khi đo chính thức; các trường chưa có được ghi `null`, không tạo số liệu giả. Không dùng kết quả của run fixture hoặc snapshot giả lập để suy ra RAM/pin/nhiệt thực tế.

## Giới hạn hiện tại

Ứng dụng hiện chạy độc lập trên fixture. Paging 3 thật, lưu bài/ảnh offline qua Room, prefetch data, HTTP cache/ETag và WorkManager cần module `data/` của Hoàn. Module benchmark, script và số liệu thực nghiệm thuộc Tú. Báo cáo thuộc Chiến. Không sửa các module này khi tích hợp phần Hoàng.
