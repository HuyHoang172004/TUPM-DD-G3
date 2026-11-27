# Kịch bản trình bày và đo A/B

Danh sách cấu hình thực thi nằm trong `catalog.py`. Mỗi tab chọn một bài con, chạy cùng workload cho A và B, rồi hiển thị các chỉ số chính của bài đó. Các thay đổi ngoài biến đối chứng được giữ cố định; bài tổng thể dùng cấu hình gốc V1/V2/V3.

| Tab | Đối chứng A → B | Thao tác thực | Chỉ số và cách đọc |
| --- | --- | --- | --- |
| Paging 3 | Eager → Paging 20 | Mở feed 10.000 bài, cuộn; chạy lại phase RAM | Peak PSS, body bytes, feed ready, frame CPU p95. Kiểm tra tiết kiệm RAM/lưu lượng và đánh đổi độ trễ. |
| Stable keys & contentType | Không khai báo → Có key/type | Cuộn, refresh dữ liệu trong cùng LazyColumn | Frame CPU p95, jank, item compositions. Không thêm độ trễ giả để ép xuất hiện chênh lệch. |
| Giảm recomposition | Tạo ImageRequest → remember ImageRequest | Cùng hành trình cuộn và cập nhật state | Item compositions, frame CPU p95, CPU. Chỉ đánh giá kỹ thuật remember request này. |
| Kích thước & decode ảnh | 1080 → 720/360 px; hoặc decode nguồn → vùng hiển thị | Cuộn các bài có ảnh, chụp UI; phase RAM riêng | Body bytes, image ready p95, PSS. Xem ảnh UI để đánh giá chất lượng cùng hiệu năng. |
| Cache ảnh RAM | Tắt → 32 MiB; hoặc 16 → 64 MiB | Cuộn đi rồi cuộn ngược trên cùng tập ảnh | Memory hit, request mạng, image ready p95, PSS. Budget cache không phải RAM toàn app. |
| HTTP cache & ETag | Tắt → Bật | Tải lại ảnh; bài API mở lại cùng detail | Body bytes, request mạng, HTTP 304, HTTP hit. Phân biệt cache TTL với revalidation. |
| Debounce & distinct | 0 → 300 ms; hoặc tắt → bật lọc query lặp | Gõ `00001` với nhịp thực dưới 300 ms; bài distinct dùng các chuỗi tương đương sau trim | Request, body bytes, latency của cùng query cuối. Chấp nhận debounce tăng thời gian chờ để giảm request. |
| Request deduplication | 10 request độc lập → Chia sẻ request đang chạy | Mở tab Lab Android, chạy 10 consumer đồng thời đọc ID 9999, cache tắt | Request cùng ID, body cùng ID, consumer thành công, detail p95. Cần đủ 10 consumer thành công ở cả hai nhánh. |
| Prefetch có giới hạn | 0 → 4 ảnh | Cuộn tiếp hoặc đổi hướng sớm | Image p95, prefetch dùng được, byte lãng phí, tổng body. Không chỉ đo trường hợp cuộn thuận lợi. |
| Giới hạn concurrency | Không gate → 4; hoặc 6 → 2 | Workload ảnh đồng thời xen request detail trong app | Active request tối đa, detail p95, lỗi, jank. Giới hạn thấp có thể tăng latency. |
| Coroutine & cancellation | Giữ job cũ → Hủy job cũ | Đổi query khi request còn chạy; nên chọn mạng chậm | Request bị hủy, body bytes, latency query cuối, lỗi. I/O chạy ngoài main ở cả hai nhánh. |
| Room index & offline | Không index → Có index; bài Saved là kiểm tra chức năng | DB kiểm thử 10.000 hàng, 5 warmup + 31 query; bài offline lưu rồi chặn backend và đọc Saved | Query p95, offline success, request. Lưu query plan; không drop index của DB người dùng. |
| Hiệu ứng thích ứng | Bật → Tắt | Cùng hành trình cuộn với hiệu ứng có sẵn trong app | Frame CPU p95, jank, CPU. Chênh lệch nhỏ/không cải thiện vẫn là kết quả hợp lệ. |
| WorkManager | Chu kỳ 15 → 60 phút | Kiểm tra WorkInfo thực; tùy chọn quan sát thật 3 giờ/nhánh | Chu kỳ đã lên lịch, số lịch hoạt động, worker đã chạy, body bytes. Demo lịch không chứng minh tiết kiệm pin. |
| Adaptive profiles | V2 cố định → V3 thích ứng | Mạng qua proxy; hoặc chuỗi snapshot Normal → Critical → Normal | PSS, body bytes, image p95, engine evaluate p95. Snapshot tiêm là kiểm thử logic, không phải áp lực phần cứng thật. |
| Hysteresis & cooldown | Chọn mode trực tiếp → Chống dao động | Cùng chuỗi snapshot, mỗi mẫu cách 5 giây | Số lần đổi mode, phản ứng Critical, thời gian phục hồi, engine p95. Chỉ tính đoạn snapshot tiêm. |
| Tổng thể V1/V2/V3 | V1 → V2 hoặc V2 → V3 | Mở feed, cuộn, tìm kiếm; phase RAM riêng | TTID, PSS, frame CPU p95, body bytes. Kết quả của gói kỹ thuật không quy đóng góp cho từng phương pháp. |

Lazy initialization hiển thị trạng thái chưa triển khai; không có phép đo đối chứng. R8, Baseline Profiles và năng lượng emulator không có kết quả trong hệ thống này.

## Trình tự demo

1. Kiểm tra môi trường, chọn emulator và kết nối chia sẻ cửa sổ. Căn hai góc vùng màn hình Android.
2. Chọn tab, bài con và điều kiện mạng. Trình bày biến được đổi giữa A/B và giả thuyết trước khi bấm chạy.
3. Bấm **Chạy demo A/B**. Theo dõi video, dấu thao tác, trạng thái và log trong lúc UIAutomator điều khiển app thật. Các harness đồng thời chạy trong app và có log xác nhận kết quả.
4. Đọc các chỉ số kèm số cặp, mở video của từng lượt, tải báo cáo và evidence. Không chọn bỏ lượt chậm/lỗi để làm đẹp kết quả.
5. Khi thu báo cáo, dùng **Đo báo cáo · 10 cặp** và giữ cùng emulator, dataset, cửa sổ capture và tải máy chủ. Thứ tự AB/BA được đảo xen kẽ. Xem cả cải thiện, suy giảm và metric chưa đo.

Demo n=1 chỉ xác minh đường chạy và minh họa. Dưới 5 cặp không kết luận khác biệt rõ; báo cáo chính thức mặc định thu 10 cặp. Các metric đến từ phase khác nhau được ghi rõ, không xem là số đo đồng thời. Chi tiết công thức, provenance và giới hạn nằm trong [protocol](../experiments/README.md).
