# Adaptive News Data và Backend

## Chạy backend

Chạy các lệnh từ thư mục gốc. Python 3.10 trở lên và Pillow có hỗ trợ WebP.
Nếu workspace đã có `backend/generated/manifest.json`, bỏ qua bước sinh và chạy Uvicorn với dữ liệu đó.

```powershell
python -m venv backend/.venv
.\backend\.venv\Scripts\python.exe -m pip install -r backend/requirements.txt
.\backend\.venv\Scripts\python.exe -m backend.generate
.\backend\.venv\Scripts\python.exe -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

Bộ sinh tạo SQLite, 30.000 ảnh WebP và `manifest.json` trong `backend/generated/`. Manifest ghi seed 42, số bài, SHA-256 của các dòng JSON chuẩn hóa theo ID và tổng byte ảnh của từng kích thước. Mọi kích thước lấy từ cùng ảnh gốc 1080 × 720. Để tái sinh sang thư mục khác, dùng `--output <thư_mục_mới>` rồi đặt `ADAPTIVE_NEWS_DATA_DIR` trỏ tới thư mục đó khi chạy server. Có thể dùng `--jobs 1` để sinh tuần tự. Byte ảnh phụ thuộc vào bản mã hóa WebP trong Pillow.

Bản [dataset-manifest.json](dataset-manifest.json) được sao từ một lần sinh đầy đủ, dùng để đối chiếu dataset khi bàn giao. Tổng byte trong manifest mô tả kích thước các tệp ảnh.

Android emulator dùng `http://10.0.2.2:8000/`. Thiết bị thật chạy `adb reverse tcp:8000 tcp:8000` và dùng `http://127.0.0.1:8000/`. Cấu hình cleartext thuộc build debug/benchmark của ứng dụng.

```powershell
curl.exe http://127.0.0.1:8000/health
curl.exe "http://127.0.0.1:8000/v1/articles?limit=20"
curl.exe http://127.0.0.1:8000/v1/articles/1
curl.exe -sS -D - -o NUL "http://127.0.0.1:8000/v1/images/1?width=360"
```

Phân trang dùng `next_cursor` từ phản hồi trước với cùng truy vấn đã chuẩn hóa. Endpoint chi tiết trả các trường của summary ở cấp cao nhất cùng `body`. API trả lỗi xác thực theo schema `error` và `message`. Ảnh có TTL một giờ. Danh sách và chi tiết có ETag và cần tái xác thực trước khi tái sử dụng cache. API kiểm tra `If-None-Match` và trả 304 với body rỗng khi nội dung khớp.

## Tích hợp Android

- Hoàng cung cấp các kiểu trong `core/contract` theo CORE.md và ghép Room, Paging 3, Retrofit/OkHttp, Coroutines, WorkManager vào version catalog. Room dùng compiler qua KSP hoặc kapt và cần cấu hình thư mục export schema. Với WorkManager, cấu hình `WorkerFactory` bằng `NewsData.syncWorkerFactory` cho cả lúc process được hệ thống khởi tạo lại.
- Tạo một `NewsData` mỗi process với base URL, `PolicyProvider`, adapter telemetry và callback latency bài viết nếu ResourceMonitor đã sẵn sàng. Gọi `startSync` một lần với scope cấp ứng dụng. Đồng bộ dùng unique periodic work theo `syncMinutes` và thay nguyên tử một lô summary, tách khỏi bảng bài đã lưu.
- Dùng `repository` cho chi tiết và bài đã lưu, `feed.eager` cho V1 và `feed.paged` cho V2/V3. Truyền luồng truy vấn ở màn Search và luồng refresh theo màn hình nếu cần. Tạo collector và cache Paging theo lifecycle của ViewModel. Mỗi truy vấn mới bắt đầu từ cursor rỗng sau debounce 300 ms. Kích thước trang lấy ở đầu truy vấn/refresh, giữ nguyên trong mỗi PagingSource.
- Cấu hình Coil dùng `imageCalls` làm `Call.Factory` và lấy URL từ `imageUrls.url(article, policy.imageWidth)`. Dùng đúng URL đó để Coil tái sử dụng dữ liệu HTTP đã prefetch. V2/V3 chia sẻ cache HTTP đĩa 64 MiB và giới hạn tổng theo `maxConcurrentRequests` cho request từ cả Coil và Retrofit. V1 dùng dispatcher mặc định, bỏ qua cache HTTP và dedup của lớp dữ liệu, và dùng Room cho bài đã lưu. Hoàng quản lý budget cache ảnh trong RAM và cấu hình giải mã của Coil.
- Tạo `prefetcher(screenScope)` theo lifecycle màn Feed/Search. Truyền các mục ngay sau viewport theo thứ tự qua `update`. Gửi danh sách rỗng khi màn hình dừng hoặc đóng prefetcher. Tác vụ chỉ tải tối đa một ảnh prefetch đồng thời trong số mục chính sách cho phép. Khi ảnh sẵn sàng cho một mục đang hiển thị, gọi `imageDisplayed(url)`. Gọi `resetPrefetchUsage` ở đầu phiên đo khi các tác vụ phiên trước đã dừng. Khi reset cache ảnh, dừng prefetch, chờ request ảnh kết thúc hoặc hủy rồi gọi `clearHttpCache` cùng thao tác reset cache Coil.
- `DataTelemetry` chuyển tiếp `request_end`, `prefetch_end` và `prefetch_used` với đúng attrs từ CORE.md. Adapter telemetry của Hoàng thêm envelope phiên đo và ghi batch ngoài main thread. `bytes_body` là byte body thực đọc từ mạng, không gồm header/TLS. Cache source có các giá trị `network`, `cache`, `conditional_cache`, `none`. Status là mã HTTP, `cancelled` hoặc `io_error`. Callback chất lượng mạng nhận thời lượng gọi bài viết thành công cần truy cập mạng, gồm cả tái xác thực 304.
- UI có thể chạy độc lập qua `FakeNewsRepository.fromAssets(context)`. Thư mục `news_fixture` trong assets có 30 bài, placeholder WebP và các payload success, empty, error. Có thể dùng `FakeNewsRepository(emptyList())` hoặc bật `failRequests` để kiểm tra hai trạng thái tương ứng. Tái sinh assets qua `python -m backend.generate --fixtures-only`.

## Kế hoạch kiểm tra

1. Sinh dataset từ seed, kiểm tra manifest và chạy API độc lập.
2. Kiểm tra phân trang V1/V2/V3, tìm kiếm, ETag và dữ liệu fixture. Ghép repository, Room và Paging qua contract, sau đó kiểm tra cache, hủy request, prefetch và giới hạn tổng.
3. Ghép Coil, telemetry, WorkManager với app của Hoàng. Chạy smoke V1/V2/V3, mở bài đã lưu khi offline và xác minh event trong phiên đo.

```powershell
.\backend\.venv\Scripts\python.exe -m unittest backend.test_api -v
$env:ADAPTIVE_NEWS_TEST_DATA_DIR = 'backend/generated'
.\backend\.venv\Scripts\python.exe -m unittest backend.test_api -v
Remove-Item Env:ADAPTIVE_NEWS_TEST_DATA_DIR
```

Lệnh đầu tạo một dataset kiểm thử nhỏ. Lệnh thứ hai quét bộ dữ liệu đã sinh với các kích thước trang 10, 20, 40 và 100. `NewsTransportTest` kiểm tra cache hit, 304, prefetch và hủy request bằng HTTP loopback trên JVM. `NewsDatabaseTest` chạy trên Android để kiểm tra lưu và đọc offline, so sánh `EXPLAIN QUERY PLAN` và ghi thời gian truy vấn Room trước/sau khi bỏ index trong DB kiểm thử. Các kiểm tra chức năng dùng fixture độc lập với số đo thực nghiệm S1–S8.
