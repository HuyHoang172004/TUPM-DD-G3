# HƯỚNG DẪN CHI TIẾT KIỂM THỬ TRỰC TIẾP TRÊN MÁY ẢO ANDROID STUDIO (`Pixel_9` / `emulator-5554`)

**Người phụ trách:** Phạm Bá Tú (`android/benchmark/`, `experiments/`, `scripts/`)  
**Thiết bị máy ảo trên máy hiện tại:** AVD `Pixel_9` (`emulator-5554`, `sdk_gphone16k_x86_64`, API 37)

Tài liệu này hướng dẫn từng bước để bạn chạy kiểm thử trực tiếp ứng dụng **Adaptive News** và thu thập số liệu thực nghiệm trên máy ảo Android Studio sẵn có trên máy (`D:\And\sdk`, `D:\tools\jdk17\jdk-17.0.21+9`, `C:\Users\Admin\AppData\Local\Programs\Python\Python312\python.exe`).

---

## 1. Cấu hình đường dẫn công cụ trên máy của bạn

Trên máy hiện tại, các công cụ được cài đặt tại:
- **Python 3.12:** `C:\Users\Admin\AppData\Local\Programs\Python\Python312\python.exe`
- **JDK 17:** `D:\tools\jdk17\jdk-17.0.21+9` (hoặc `D:\android studio\jbr`)
- **Android SDK:** `D:\And\sdk`
- **ADB:** `D:\And\sdk\platform-tools\adb.exe`
- **Emulator:** `D:\And\sdk\emulator\emulator.exe` (AVD: `Pixel_9`, serial: `emulator-5554`)

Mở **PowerShell** tại thư mục gốc dự án `d:\TUPM-DD-G3-main` và thiết lập biến môi trường cho phiên làm việc:

```powershell
$env:JAVA_HOME = "D:\tools\jdk17\jdk-17.0.21+9"
$env:ANDROID_HOME = "D:\And\sdk"
$env:ANDROID_SDK_ROOT = "D:\And\sdk"
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_SDK_ROOT\platform-tools;$env:ANDROID_SDK_ROOT\emulator;C:\Users\Admin\AppData\Local\Programs\Python\Python312;$env:PATH"
```

Kiểm tra máy ảo đang hoạt động:

```powershell
adb devices
# Kết quả mong đợi:
# List of devices attached
# emulator-5554   device
```

*(Nếu máy ảo `Pixel_9` chưa bật, bạn có thể mở từ **Device Manager** trong Android Studio hoặc chạy `emulator -avd Pixel_9`).*

---

## 2. Bước 1: Khởi chạy Backend FastAPI và bộ dữ liệu chuẩn (Seed 42)

Máy ảo Android truy cập máy chủ trên máy host thông qua địa chỉ đặc biệt `http://10.0.2.2:8000/`. Mở một cửa sổ PowerShell riêng tại `d:\TUPM-DD-G3-main` để chạy Backend:

```powershell
# 1. Tạo môi trường ảo Python và cài đặt thư viện (chỉ cần chạy lần đầu)
C:\Users\Admin\AppData\Local\Programs\Python\Python312\python.exe -m venv backend\.venv
.\backend\.venv\Scripts\python.exe -m pip install -r backend\requirements.txt

# 2. Sinh bộ dữ liệu chuẩn 10.000 bài viết và 30.000 ảnh WebP (360px, 720px, 1080px)
.\backend\.venv\Scripts\python.exe -m backend.generate --jobs 4

# 3. Khởi chạy máy chủ FastAPI tại cổng 8000
.\backend\.venv\Scripts\python.exe -m uvicorn backend.main:app --host 127.0.0.1 --port 8000
```

Kiểm tra nhanh Backend đã sẵn sàng ở cửa sổ PowerShell thứ hai:

```powershell
curl.exe http://127.0.0.1:8000/health
# Kết quả: {"status":"ok"}
```

---

## 3. Bước 2: Biên dịch và cài đặt ứng dụng lên máy ảo `emulator-5554`

Ứng dụng có 2 chế độ build phục vụ kiểm thử:
- **`debug`**: Dùng khi muốn thao tác tay trực tiếp trên giao diện máy ảo hoặc dùng Android Studio Profiler.
- **`benchmark`**: Bản dựng tối ưu (`isProfileable = true`, `isDebuggable = false`, cho phép HTTP cleartext nội bộ) bắt buộc dùng khi chạy kịch bản đo S1–S8 và nhận Intent extras tự động (`variant`, `run_id`, `scenario_id`).

Chạy lệnh biên dịch và cài bản `benchmark` lên máy ảo:

```powershell
Push-Location android
.\gradlew.bat :app:assembleBenchmark
adb -s emulator-5554 install -r app\build\outputs\apk\benchmark\app-benchmark.apk
Pop-Location
```

---

## 4. Bước 3: Kiểm thử trực tiếp bằng tay trên màn hình máy ảo (Interactive UI & Lab Test)

Sau khi cài ứng dụng lên máy ảo `Pixel_9`:

1. **Kiểm tra màn hình Tin (Feed):**
   - Mở ứng dụng **Adaptive News** trên máy ảo.
   - Cuộn danh sách bài viết để quan sát ảnh thumbnail tải từ `http://10.0.2.2:8000/v1/images/{id}?width=...`.
2. **Kiểm tra màn hình Tìm (Search) & Chi tiết (Detail):**
   - Chuyển sang tab **Tìm** (`⌕`), nhập `"công nghệ 00001"`.
   - Sau 300 ms debounce, danh sách hiển thị kết quả khớp tiêu đề. Chạm vào bài **`Tin công nghệ 00001`** để mở trang **Chi tiết**.
3. **Kiểm tra Lưu bài & Đọc ngoại tuyến (Saved / Offline — Kịch bản S5):**
   - Trong trang Chi tiết của bài `Tin công nghệ 00001`, bấm nút **Lưu bài** (nút chuyển thành **Bỏ lưu**).
   - Ngắt kết nối mạng của máy ảo bằng lệnh:
     ```powershell
     adb -s emulator-5554 shell svc wifi disable
     adb -s emulator-5554 shell svc data disable
     ```
   - Chuyển sang tab **Đã lưu** (`★`), chạm vào bài `Tin công nghệ 00001` và xác nhận toàn bộ nội dung `detail_body` vẫn mở và đọc được khi mất mạng hoàn toàn.
   - Bật lại mạng cho máy ảo:
     ```powershell
     adb -s emulator-5554 shell svc wifi enable
     adb -s emulator-5554 shell svc data enable
     ```
4. **Kiểm tra Bộ điều phối thích ứng trên màn hình Lab (Kịch bản S6):**
   - Chuyển sang tab **Lab** (`⚙`).
   - Tại mục **Phiên bản**, chọn **`V3`** (ứng dụng sẽ tự khởi động lại ở chế độ `V3` thích ứng). Mở lại tab **Lab**.
   - Bấm nút **`Critical`**: Quan sát dòng trạng thái đổi ngay lập tức sang `Chế độ: ECO · memory_critical`, kích thước ảnh hạ xuống `360px`, `Trang 10`, `Cache 16 MiB`, `Prefetch 0`, `Request 2`, `Hiệu ứng false`.
   - Bấm nút **`Normal`**: Quan sát cơ chế **Hysteresis & Cooldown** — chế độ sẽ giữ ở `ECO` cho đến khi đủ **3 mẫu liên tiếp** (mỗi mẫu 5 giây) và tối thiểu **30 giây** kể từ lần đổi mode trước mới nâng cấp lên.
   - Bấm nút **`Slow`**: Quan sát ràng buộc mạng tức thời hạ `Prefetch` và `Request` xuống `≤ 2`.
   - Bấm **`Dùng trạng thái thật`** để quay về tín hiệu thiết bị thực, hoặc bấm **`Xuất log JSONL`** để lưu tệp telemetry ra bộ nhớ máy ảo.

---

## 5. Bước 4: Chạy kiểm thử tự động các kịch bản S1–S7 qua Script của Tú

Bạn có thể chạy tự động các kịch bản đo từ cửa sổ PowerShell tại `d:\TUPM-DD-G3-main`:

### 5.1. Chạy Smoke Test nhanh (kiểm tra luồng đầu-cuối trên máy ảo)

Lệnh dưới đây chạy thử nghiệm kịch bản **S1 (Startup)** và **S2 (Feed Scroll)** qua cả 3 phiên bản `V1`, `V2`, `V3` với 1 lần lặp (`--repeats 1`, `--no-warmup`, thời gian cuộn nhanh 10 giây) để bạn kiểm chứng ngay pipeline trên máy ảo:

```powershell
python scripts\run_experiments.py `
  --device emulator-5554 `
  --scenarios S1 S2 `
  --variants V1 V2 V3 `
  --cache-states cold `
  --repeats 1 `
  --no-warmup `
  --scroll-seconds 10 `
  --raw-dir experiments\raw_smoke `
  --processed-dir experiments\processed_smoke
```

Sau khi chạy xong:
- Dữ liệu thô của từng lượt chạy (`metadata.json`, `events.jsonl`, `meminfo.txt`, `gfxinfo.txt`) nằm tại `experiments/raw_smoke/<run_id>/`.
- Bảng kết quả 25 cột nằm tại `experiments/processed_smoke/run_metrics.csv`.
- Bảng thống kê tổng hợp nằm tại `experiments/processed_smoke/summary_metrics.csv`.

### 5.2. Chạy kiểm thử mạng chậm (Kịch bản S3 với Network Shaping trên Emulator)

Máy ảo Android hỗ trợ giả lập băng thông và độ trễ mạng trực tiếp:

```powershell
# Bật shaping mạng chậm (1 Mbps, RTT 250 ms)
adb -s emulator-5554 emu network delay 250
adb -s emulator-5554 emu network speed 1000 1000

# Chạy kịch bản S3
python scripts\run_experiments.py `
  --device emulator-5554 `
  --scenarios S3 `
  --variants V1 V2 V3 `
  --cache-states cold `
  --repeats 5 `
  --network-profile "slow_1mbps_250ms"

# Khôi phục mạng tốc độ đầy đủ sau khi đo xong S3
adb -s emulator-5554 emu network delay 0
adb -s emulator-5554 emu network speed full
```

### 5.3. Chạy đợt đo chính thức đầy đủ (Official Benchmark Run)

Khi đã đóng băng mã nguồn (`freeze commit`) để lấy số liệu chính thức cho báo cáo của Chiến:

```powershell
python scripts\run_experiments.py `
  --device emulator-5554 `
  --scenarios S1 S2 S4 S5 S6 S7 `
  --variants V1 V2 V3 `
  --cache-states cold warm `
  --repeats 5 `
  --raw-dir experiments\raw `
  --processed-dir experiments\processed
```
