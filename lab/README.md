# Adaptive News Performance Lab

Web local điều khiển app trên Android emulator, so sánh A/B từng kỹ thuật và giữ video/log/trace. Không có số đo giả hoặc nút đo chỉ chạy animation trên web.

Xem [kịch bản cho từng tab](SCENARIOS.md) để trình bày đối chứng, thao tác và ý nghĩa các chỉ số.
Các lượt pilot đã chạy và phạm vi kiểm thử được ghi trong [xác minh triển khai](VALIDATION.md).

## Chạy tự động bằng một file

Trên Windows x64 Intel/AMD, nhấp đúp **`run-lab.cmd`** ở thư mục gốc hoặc chạy:

```powershell
.\run-lab.cmd
```

Không cần cài trước Python, Node, JDK hay Android Studio. PowerShell launcher sẽ:

1. Dùng Python/Node phù hợp nếu đã có; nếu thiếu thì tải Python 3.12 qua uv và Node portable.
2. Chuẩn bị JDK đầy đủ, Android command-line tools, SDK platform 36, build-tools, platform-tools, emulator và AOSP system image Android 35.
3. Cài thư viện Python/Node, build web, tạo dataset chuẩn 10.000 bài/30.000 ảnh, tải Perfetto và build APK.
4. Dùng lại AVD `AdaptiveNewsLab` hoặc tạo/mở AVD đó; đợi Android boot và cài hai APK nếu checksum trên máy ảo khác bản build.
5. Bật backend, proxy, dashboard rồi tự mở Chrome/Edge. Nếu máy chưa có cả hai trình duyệt thì tải Chromium qua Playwright.

Lần đầu cần internet và tối thiểu 12 GB trống trên ổ chứa dự án để bắt đầu chuẩn bị Android; lượng tải vài GB, thời gian tùy mạng và máy. Các công cụ/cache tải thêm nằm trong `.lab`, không đưa lên Git. Launcher không thay PATH toàn hệ thống hoặc đăng ký Python toàn máy; biến môi trường được dùng trong phiên chạy rồi phục hồi. Việc cài SDK tự chấp nhận các [điều khoản SDK](https://developer.android.com/studio/terms) cho các package cần thiết.

Máy cần bật **VT-x/SVM trong BIOS và Windows Hypervisor Platform**. Nếu thiếu, script báo hướng dẫn rồi dừng; không tự thay BIOS, bật Windows feature hoặc reboot. Xem [hướng dẫn chính thức của Android](https://developer.android.com/studio/run/emulator-acceleration). Sau khi bật và restart Windows, chạy lại cùng file; tài nguyên đã chuẩn bị sẽ được dùng lại.

Giữ cửa sổ terminal mở; **Ctrl+C** dừng server. Lần chạy sau kiểm tra checksum nguồn, phiên bản thư viện, dataset và APK để bỏ qua bước đã xong. Nếu Lab của cùng checkout đang mở, launcher chỉ mở lại trang, không chạy thêm server. Không đóng một launcher đang chuẩn bị để mở thêm launcher khác.

Trình duyệt vẫn yêu cầu bạn chọn chia sẻ cửa sổ emulator bằng nút **Kết nối evidence**, rồi căn vùng Android. Quyền chia sẻ màn hình không thể được tự cấp bởi script.

Các tùy chọn:

```powershell
.\run-lab.cmd -NoBrowser       # Chuẩn bị và chạy server, không tự mở trình duyệt.
.\run-lab.cmd -SetupOnly       # Chuẩn bị tài nguyên/build, chưa boot/cài APK/chạy server.
.\run-lab.cmd -Refresh         # Cài lại thư viện và build lại; đóng Lab đang chạy trước.
# Hoặc gọi PowerShell trực tiếp:
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\launch-lab.ps1
```

`run-lab.cmd` dùng execution policy riêng cho tiến trình PowerShell đó, không đổi policy của máy. Log chuẩn bị ở `.lab/logs/launcher-*.log`; log backend/proxy ở `.lab/backend-error.log` và `.lab/proxy.log`. Nếu mất mạng hoặc đóng giữa chừng, chạy lại file để tiếp tục kiểm tra/cài phần còn thiếu. Dataset chưa hoàn chỉnh được chuyển sang `backend/.dataset-previous-*` để giữ lại trước khi tạo lại. Launcher không tự tắt dịch vụ khác khi các cổng 8000/8001/8765 đang bận.

Các bản tải bootstrap được cố định URL/phiên bản và kiểm tra SHA-256: [Node](https://nodejs.org/dist/v22.23.5/SHASUMS256.txt), [uv](https://github.com/astral-sh/uv/releases/tag/0.13.10), [Microsoft OpenJDK](https://learn.microsoft.com/en-us/java/openjdk/download), Android command-line tools 19.0 và Perfetto v59.1. uv quản lý bản Python tải về; SDK Manager/Gradle/npm/pip quản lý các package tương ứng. Git không bắt buộc khi dùng bản ZIP; báo cáo sẽ ghi rõ không xác minh được source revision nếu thiếu Git hoặc metadata repository.

## Cài thủ công cho phát triển

Cần Windows/PowerShell, Python 3.10+, Node 20.19+ hoặc 22.12+, JDK 17/21 đầy đủ (có `jlink`), Android SDK platform 36 + build-tools 35.0.0 (mặc định AGP) + platform-tools. Script tạo AVD cần thêm Android SDK Command-line Tools (latest). Đặt `JAVA_HOME`, `ANDROID_HOME`; tùy chọn `LAB_ADB`. SDK trong `.lab/android-sdk` được tự nhận ADB. Chrome/Edge dùng để chia sẻ và ghi WebM.

Từ thư mục gốc:

```powershell
.\scripts\setup-lab.ps1
.\.lab\venv\Scripts\python.exe -m backend.generate
# Mở AVD trong Android Studio Device Manager (API 31+, khuyến nghị 35).
.\scripts\build-benchmark.ps1 -DeviceSerial emulator-5554
.\scripts\start-lab.ps1
```

Mở **http://127.0.0.1:8765**. `setup-lab.ps1 -SkipAndroid` chỉ chuẩn bị web/runner, chưa có phép đo khi thiếu emulator/APK. Script không cài system image hoặc bật hypervisor. Cài SDK/AVD bằng Android Studio SDK Manager và Device Manager nếu máy chưa có.

Nếu backend đã chạy: `start-lab.ps1 -SkipBackend`. Dataset ở vị trí khác: `ADAPTIVE_NEWS_DATA_DIR`. Cổng backend 8000, proxy 8001, dashboard/runner 8765; chỉ bind loopback.

## Demo với thầy

1. Mở cửa sổ emulator riêng. Bấm **Kiểm tra môi trường**, xử lý mục chưa đạt.
2. Bấm **Kết nối evidence**, chọn cửa sổ emulator, không chọn tab dashboard.
3. Bấm góc trên trái rồi góc dưới phải vùng màn hình Android trong preview, bỏ thanh tiêu đề/toolbar. Đây là căn tọa độ để dấu chạm khớp video.
4. Chọn phương pháp, bài con, mạng; chạy demo A/B. Workload cuộn demo 20 giây/nhánh. Phương pháp có PSS chạy thêm lượt RAM riêng. Chuỗi ngữ cảnh chạy đủ số mẫu 5 giây, không bị rút thành 20 giây.
5. Theo dõi app thật đang nhập/cuộn/mở bài. Dấu thao tác là annotation của lệnh được UIAutomator xác nhận, không thay video gốc.
6. Xem video từng lượt; xuất CSV, HTML in PDF hoặc ZIP evidence.
7. **Đo báo cáo** chạy 10 cặp AB/BA; cuộn 60 giây/lượt. Không gộp demo với báo cáo hoặc suy rộng số đo emulator sang điện thoại thật.

Giữ emulator hoạt động, không thu nhỏ và không để host sleep. Giữ refresh rate/độ phân giải/host workload giống nhau. Warmup 2 giây mỗi trial ngoài khoảng đo, sau đó reset image cache và process. Cùng APK, `CompilationMode.Ignore`; trạng thái biên dịch và AB/BA được ghi trong metadata.

Mất capture/heartbeat/thiết bị, lỗi selector hoặc thiếu xác nhận cấu hình: lượt bị failed/incomplete và giữ dữ liệu lỗi. Không tự chạy lại để che thất bại. Refresh trang làm mất capture; lượt đó không được coi là hoàn tất.

## Đối chứng và giới hạn

Registry `catalog.py` là nguồn chung cho UI và runner. `ExperimentConfig` chỉ tác dụng trong benchmark build; debug/release giữ hành vi cũ. Provider điều khiển chỉ có trong benchmark manifest và chỉ chấp nhận shell/app UID.

- Có Paging, stable keys, remember ImageRequest, server resize, decode size, memory cache, HTTP/ETag, debounce/distinct, dedup, prefetch, concurrency, cancellation, Room/offline, animation, WorkManager, adaptive/hysteresis và V1/V2/V3.
- Bài riêng tắt các cache/prefetch không liên quan; budget cache không phải PSS toàn app. Coil disk cache tắt trong các đối chứng riêng.
- Dedup/concurrency là workload nhiều consumer trong app, không giả định 10 lần chạm tạo 10 consumer đồng thời. UIAutomator chuyển tới Lab thật và app hiển thị phương pháp/nhánh.
- Cancellation giữ I/O ngoài main và eager query giống nhau; chỉ đổi hủy job cũ. Chọn mạng chậm để thấy request chồng nhau.
- Query index dùng Room in-memory riêng với schema và DAO thật, 10.000 hàng, 5 warmup và 31 query đo. Chỉ drop index trong DB kiểm thử; không thay dữ liệu bài lưu thật. Latency gồm query, mapping và lần đọc Flow đầu tiên.
- Offline chặn backend tại proxy và chờ acknowledgement trước khi đọc Saved. Đây là không cần backend, không gọi là mất kết nối vật lý thiết bị.
- WorkManager demo xác minh unique periodic work và cấu hình chu kỳ, không rút ngắn thời gian để suy ra pin. Quan sát dài chạy 3 giờ/nhánh, ghi số job thực chạy, không đòi OS chạy chính xác từng phút.
- WebP dùng chung; không tuyên bố riêng codec. Lazy initialization/R8/Baseline Profiles chưa có đối chứng. Năng lượng emulator là N/A.
- Startup là TTID; ảnh/search/detail là ready theo callback. Engine evaluate không bao gồm toàn bộ chi phí áp dụng policy. Xem `../experiments/README.md` để biết công thức/raw.

## Kiểm thử và phát triển

```powershell
.\.lab\venv\Scripts\python.exe -m unittest lab.test_analysis lab.test_app lab.test_proxy backend.test_api -v
.\.lab\venv\Scripts\python.exe -m unittest lab.test_bootstrap -v
.\scripts\test-bootstrap.ps1
cd dashboard
npm.cmd test
npm.cmd run build
# Runner đang chạy 8765 và Chrome đã cài:
npm.cmd run test:e2e
```

Dev web: `npm.cmd run dev`, Vite proxy `/api` về runner. Demo chính thức phục vụ `dashboard/dist`. Test fixture không thay phép đo emulator. Không commit `.lab`, node_modules, APK, dataset ảnh hay video/raw lớn. Raw ở `experiments/raw/<run_id>/`; report ghi commit và cờ dirty. Tổng hợp lại: `python scripts/analyze_results.py <run_id>`.

Smoke test có video thật, khi Lab và emulator đã sẵn sàng:

```powershell
node scripts/smoke-live.mjs --method dedup --case default
node scripts/smoke-live.mjs --method paging --case default
```

Script dùng Chrome tự chọn đúng cửa sổ `Android Emulator - AdaptiveNewsLab:5554`, không dùng fake media. Có thể đổi bằng `--window-title`. Trong UI bình thường người trình bày chọn cửa sổ bằng hộp thoại của trình duyệt.

Nếu Gradle 8.13 trên Windows báo không move được immutable workspace, đợi build dừng rồi chạy `scripts/repair-gradle-cache.ps1` trước khi build lại. Script chỉ copy transform đã hoàn tất trong cache `.lab` của dự án, không xóa cache hoặc tắt antivirus.
