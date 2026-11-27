# Dữ liệu thực nghiệm

Không commit số liệu được sinh để minh họa. Dashboard chỉ dùng dữ liệu từ lượt chạy thực.

Mỗi `raw/<run_id>/` chứa `run.json`, `progress.jsonl`, `summary.json`, `run_metrics.csv`, `report.html` và `manifest.json` (SHA-256). Mỗi trial có `config.json`, `events.jsonl`, `device_live.jsonl`, `proxy.jsonl` nếu có request, `pss.jsonl` ở phase memory, `instrumentation.txt`, thư mục `benchmark/` và `evidence.webm`.

Mười cặp AB/BA, một iteration Macrobenchmark mỗi trial; một cặp demo không dùng kết luận thống kê. Phase RAM chạy lại cùng workload, lấy PSS mỗi 2 giây. Không gộp frame và RAM từ các phase thành số đo cùng thời điểm. Snapshot tiêm chỉ dùng kiểm thử ngữ cảnh. WorkManager schedule là xác minh cấu hình thực, chưa phải mô phỏng thời gian hay phép đo pin. Quan sát nền dùng thời gian thực 3 giờ/nhánh. Năng lượng trên emulator là N/A.

Startup là TTID. `feed_ready`, `search_ready`, `detail_ready`, `image_visible` được ghi theo định nghĩa của app (dữ liệu/ảnh sẵn sàng), không tự coi là thời điểm photon hiển thị. Frame timing từ Macrobenchmark; jank từ Perfetto FrameTimeline nếu trace và trace processor hỗ trợ. Không suy jank bằng ngưỡng 16,67 ms cho mọi thiết bị.

Config `protocol=2`: feed kết thúc khi UI có danh sách không rỗng; search kết thúc khi UI xác nhận refresh của đúng query đã xong. Cả eager và Paging dùng callback UI; query rỗng lúc mở feed không chịu debounce tìm kiếm trong bài đối chứng riêng. Pilot protocol 1 chỉ dùng kiểm tra đường chạy, không gộp với protocol 2 để so latency feed/search. Protocol config khác với phiên bản bridge ADB (`ping protocol=1`).

`final_search_ms` chỉ lấy xác nhận của cùng truy vấn cuối sau lần nhập cuối, không so p95 của nhiều truy vấn trung gian ở A với một truy vấn ở B. Raw vẫn giữ mọi `query_input`/`search_ready`. Runner từ chối bài debounce nếu khoảng cách ký tự thực trong app đạt 300 ms; việc gửi phím không thay thế bước kiểm tra timestamp này.

Body bytes lấy từ byte app thực đọc qua network interceptor; không gồm header/TLS. Proxy ghi byte server gửi để đối chiếu, có thể khác byte client đã đọc khi hủy request. Không cộng hai nguồn thành tổng lưu lượng. Video ghi trên host ở mục tiêu 15 FPS/1280 px và 2 Mbps, không screenrecord trong Android. Lưu cấu hình capture thực với mỗi trial. Dấu chạm là lớp dựng từ action log; video gốc không bị thay đổi. Mốc phát lại dùng thời gian runner nhận capture/action, có sai số truyền log.

Khoảng đo bắt đầu khi Activity tạo telemetry trước khi bind data, kết thúc sau workload do bridge xác nhận. Thao tác chuẩn bị/reset không tính. PSS là max mẫu quan sát được, không phải Java heap hoặc peak liên tục. CPU lấy từ monitor và chuẩn hóa theo tổng core.

Mỗi metric summary có n theo nhánh, median, Q1, Q3, số cặp, delta và bootstrap 95% (2000 mẫu, seed 42). Chỉ dùng cặp A/B đủ cả hai nhánh và đúng phase để tính point estimate và CI. Dưới 5 cặp chỉ mô tả, không kết luận rõ. Không có số đo để null/trống. Baseline 0 không tính phần trăm. Lượt lỗi giữ raw và không tham gia summary; các cặp hoàn chỉnh trước khi batch gặp lỗi vẫn được giữ, với trạng thái batch thể hiện rõ.

Đếm composition dùng counter, ghi tổng một lần ở cuối lượt, không ghi JSON theo từng frame. Các bài hysteresis/context tiêm tính số lần đổi mode và thời gian phản ứng trên phần `source=simulated`, tách khỏi chuyển chế độ lúc startup thật. Jank là tỷ lệ frame của app được FrameTimeline gắn `jank_type != 'None'`, gồm cả Prediction Error/Buffer Stuffing của emulator, không chỉ App Deadline Missed.

Raw được giữ nguyên sau khi run kết thúc. `python scripts/analyze_results.py <run_id>` chỉ tổng hợp lại báo cáo từ metric đã lưu. ZIP được tạo khi tải, không trong lúc benchmark.
