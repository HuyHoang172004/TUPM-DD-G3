# Xác minh triển khai

Branch: `feature/performance-lab`. Các lượt dưới đây chạy thật trên AVD Android 35, dataset 10.000 bài, mạng proxy 20 Mbps cộng 40 ms response delay. Mỗi bài chỉ có **một cặp A/B demo**, không đủ để kết luận thống kê hoặc suy rộng sang thiết bị thật. Workload có chia sẻ và ghi cửa sổ emulator, không dùng fake media.

| Bài | Run ID | Số đo quan sát A → B |
| --- | --- | --- |
| Request deduplication | `R20261127T175736_4018be` | Request tới ID 9999: 10 → 1; body cùng ID: 5.370 → 537 bytes; cả hai nhánh đủ 10 consumer thành công. |
| Paging 3 | `R20261127T182354_17f5f7` | Peak PSS: 84,14 → 71,89 MiB; body mạng: 2.613.842 → 645.139 bytes; feed ready: 1.000 → 1.241 ms; frame CPU p95: 45,66 → 39,33 ms. |
| Debounce | `R20261127T182740_604846` | Request mạng: 12 → 8; body: 95.853 → 81.567 bytes; cùng query cuối `00001` sẵn sàng: 109 → 460 ms. Nhịp ký tự thực trong app đã qua điều kiện dưới 300 ms. |
| WorkManager schedule | `R20261127T182152_be3a0d` | WorkInfo xác nhận chu kỳ 15 → 60 phút; mỗi nhánh một lịch hoạt động; không có worker hoàn tất trong khoảng demo. Đây là xác minh lịch, không phải kết quả pin. |

Paging có bốn lượt: A/B hiệu năng và A/B RAM riêng. Dedup là pilot protocol 1; các chỉ số request/body/consumer không phụ thuộc mốc feed/search. Paging và debounce ở bảng dùng protocol 2. Giữ các pilot cũ/lỗi trong lịch sử nhưng không gộp chúng vào kết quả trên.

Raw, cấu hình, log, trace, PNG, WebM và report nằm tại `experiments/raw/<run_id>/` trên máy này; thư mục đó được gitignore vì chứa dữ liệu/video lớn. Có thể mở lại qua lịch sử dashboard. Không có số liệu mẫu được nạp sẵn vào dashboard ở checkout mới.

Đã xác minh:

- Build cả app benchmark và APK kiểm thử, cài được trên emulator; 13 Android debug unit tests đạt.
- 34 kiểm thử Python cho phân tích, API, proxy và backend đạt.
- 3 kiểm thử frontend, 4 kiểm thử trình duyệt và production build đạt; kiểm tra cả màn hình mobile 390 px.
- Video evidence phát lại được trong Chrome, không có lỗi JavaScript tại lần kiểm tra.
- ZIP của Paging và WorkManager tải được qua API; toàn bộ 37 và 23 tệp tương ứng khớp kích thước và SHA-256 manifest. CSV/HTML tải được.

Chưa chạy báo cáo đủ 10 cặp cho từng phương pháp, chưa chạy quan sát WorkManager 6 giờ, và chưa smoke-test mọi bài con trên emulator. Những phần này có luồng thực thi trong dashboard nhưng không được coi là đã có kết quả thực nghiệm. Các metric chưa thu được hiển thị N/A; Lazy initialization chưa có đối chứng nên bị khóa.

## Launcher tự động

`run-lab.cmd` / `scripts/launch-lab.ps1` đã được kiểm tra trên Windows PowerShell 5.1:

- Tải thật uv và Node, kiểm tra SHA-256, cài managed Python 3.12.16 và Node 22.23.5 trong một thư mục thử riêng không có sẵn runtime.
- Python tải về tạo được venv mới, cài đủ requirements backend/Lab và qua bước đối chiếu phiên bản.
- Chạy setup trên workspace hiện có, xác minh đầy đủ 30.000 ảnh theo số lượng/kích thước, build APK/web và tải Perfetto. Binary Perfetto local thực thi được query kiểm tra.
- Launcher mở Chrome tới dashboard; preflight trả `ready=true`. Chạy lại nhận diện instance sẵn có, không mở server trùng.
- Khởi động từ cache bỏ qua cài dependency/build, tái sử dụng đúng AVD và bỏ qua cài APK khi SHA-256 trùng bản trong emulator.
- Dừng bằng Ctrl+C qua terminal: backend, proxy và dashboard đều thoát. Với wrapper CMD, Windows có thể hỏi `Terminate batch job (Y/N)?`; chọn `Y` để đóng wrapper.
- 20 kiểm tra bootstrap PowerShell và 42 kiểm thử Python đạt. Bao gồm ZIP traversal, đường dẫn ra ngoài workspace, cache, argv có dấu cách/ký tự shell, timeout, CRCRLF của ADB, dataset thiếu ảnh và trường hợp không có Git.

Chưa thử toàn bộ quá trình cài Android SDK trên một Windows sạch trong máy ảo riêng: phần SDK/JDK của lượt tích hợp dùng tài nguyên đã chuẩn bị ở workspace này. BIOS/Windows Hypervisor Platform được kiểm tra và báo lỗi nếu thiếu, không được tự thay đổi bởi launcher.
