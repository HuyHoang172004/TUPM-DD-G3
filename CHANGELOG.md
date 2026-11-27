# Changelog

## Performance Lab — feature/performance-lab

- Thêm `run-lab.cmd` và launcher PowerShell: bootstrap Python/Node/JDK/Android SDK/Perfetto, tạo dataset, build có cache, boot AVD, cài APK theo SHA-256 và mở trình duyệt sau khi server sẵn sàng. Khóa chống chạy trùng, giữ log và tài nguyên đã tải để chạy lại.
- Nhận diện instance theo checkout qua `/api/instance`. Bản ZIP không có Git vẫn chạy được và ghi source revision chưa xác minh; launcher không giả commit hoặc số đo.

- Thêm dashboard local React/TypeScript/ECharts, runner FastAPI, registry A/B, proxy giới hạn băng thông tổng, video WebM có thứ tự và SHA-256 evidence manifest.
- Thêm module Macrobenchmark/UIAutomator và shell-only provider chỉ có trong benchmark build. Override độc lập qua ExperimentConfig; giữ contract Policy và ý nghĩa V1/V2/V3.
- JSONL schema_version=1 bổ sung `experiment_config`, `measure_start/end`, `feed_ready`, `request_started`, `composition_count`, `consumer_complete`, `room_query`, `query_plan`, `schedule_checked`, `harness_end`, `offline_result`, `worker_end`. Đếm composition bằng counter và xuất một lần ở cuối lượt, không ghi JSON theo frame.
- `image_visible` hiện là callback image-ready, không phải mốc photon/display hoàn tất. Run chính thức kết thúc bằng mốc runner thay vì Activity destruction. Trace/log/video/config nối qua trial ID.
- Thêm kiểm thử phân tích/API/proxy/frontend/browser, script setup/build/start và protocol. Không tạo số đo pin/năng lượng trên emulator.
- `/health` giữ body cũ, bổ sung header `X-Adaptive-News-Dataset` để preflight đối chiếu hash dataset đang được server phục vụ.
