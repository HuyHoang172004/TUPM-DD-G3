# GIAO THỨC ĐO ĐẠC VÀ THỰC NGHIỆM (EXPERIMENTAL PROTOCOL) — ADAPTIVE NEWS

**Chủ sở hữu:** Phạm Bá Tú (`benchmark/`, `experiments/`, `scripts/`)  
**Phiên bản:** 1.0  
**Tham chiếu:** `Adaptive_News_CORE.md` (Mục 9, 10, 11)

---

## 1. Mục tiêu và nguyên tắc thực nghiệm

Giao thức này chuẩn hóa toàn bộ quy trình thiết lập môi trường, điều khiển kịch bản S1–S8, thu thập dữ liệu thô (`experiments/raw/<run_id>/`) và tổng hợp bảng chỉ số (`experiments/processed/run_metrics.csv`, `summary_metrics.csv`) nhằm trả lời 3 câu hỏi nghiên cứu:
- **RQ1 (Tối ưu tĩnh so với Baseline):** Đối sánh `V2` với `V1` trên cùng kịch bản và trạng thái bộ nhớ đệm (`cold`/`warm`).
- **RQ2 (Điều phối thích ứng trong điều kiện bất lợi):** Đối sánh `V3` với `V2` trong điều kiện mạng chậm (S3), biến động ngữ cảnh (S6) và tải kéo dài (S7/S8).
- **RQ3 (Chi phí điều phối và đánh đổi):** Định lượng độ trễ đánh giá của engine (`engine_p50_ms`, `engine_p95_ms`), số lần chuyển chế độ (`mode_switches`), chi phí áp dụng chính sách và sự đánh đổi giữa kích thước ảnh (`360/720/1080 px`), lưu lượng mạng (`network_body_bytes`) và độ trễ hiển thị ảnh (`image_p95_ms`).

> **Nguyên tắc bắt buộc:** Không tạo số liệu thực nghiệm giả. Mọi con số trong báo cáo phải truy xuất được về đúng `run_id`, `commit`, `config_hash` và tệp log/trace gốc trong `experiments/raw/<run_id>/`.

---

## 2. Phiếu thông số môi trường chuẩn bị trước đợt đo

Trước khi chạy đợt đo chính thức, người đo cần ghi nhận đầy đủ các thông số sau vào `metadata.json` của từng lượt chạy:

| Trường thông số | Khóa trong `metadata.json` | Quy định kiểm soát |
|---|---|---|
| Thiết bị, SoC, RAM, tần số quét | `device`, `soc`, `ram_mib`, `refresh_rate_hz` | Cố định một thiết bị duy nhất cho toàn bộ chuỗi so sánh V1/V2/V3 |
| Hệ điều hành, API level, fingerprint | `os_version`, `api_level`, `build_fingerprint` | Thiết bị có API < 29 ghi nhận trạng thái nhiệt `UNKNOWN` |
| Mã commit ứng dụng & backend | `commit`, `config_hash` | Đóng băng commit (`freeze commit`) trước đợt đo chính thức |
| Cấu hình build | `build_type`, `profileable` | Bắt buộc dùng bản dựng `benchmark` (`non-debuggable`, `profileable=true`) |
| Bộ dữ liệu chuẩn | `dataset_seed`, `dataset_sha256` | Seed `42`, 10.000 bài viết, khớp `backend/dataset-manifest.json` (`b0adc4ef5b5944f3f9d379536aca8de391b051cb78cfc1a65ff99a31250abf79`) |
| Cấu hình mạng và shaping | `network_profile`, `bandwidth_mbps`, `rtt_ms` | Ghi rõ thông số shaping thực tế (Mạng tốt: 20 Mbps / 40 ms; Mạng chậm: 1 Mbps / 250 ms) |
| Độ sáng, mức pin, trạng thái nhiệt | `screen_brightness`, `battery_start_pct`, `thermal_start` | Cố định độ sáng màn hình, tắt tự động điều chỉnh độ sáng |
| Trạng thái cache và chế độ reset | `cache_state`, `reset_mode` | Tách biệt hoàn toàn `cold` và `warm`; không gộp chung |

---

## 3. Quy tắc lặp, xen kẽ và kiểm soát trạng thái bộ nhớ đệm

1. **Số lần lặp tối thiểu (`repeat`):**
   - Mỗi tổ hợp `scenario_id × variant × cache_state` chạy tối thiểu **5 lần lặp hợp lệ** (`n >= 5`), khuyến nghị **10 lần**.
   - Riêng kịch bản khởi động **S1 (Startup)** ưu tiên **≥ 10 lần lặp** cho mỗi nhóm `cold` và `warm`.
   - Các nhóm có `n < 5` trong quá trình chạy thử nghiệm (pilot) phải được gắn cờ `insufficient_repeats=true` trong bảng tổng hợp.
2. **Tách biệt lượt chạy làm nóng (`warmup`):**
   - Lượt chạy `warmup` ban đầu được tách riêng (`is_warmup=true` hoặc `repeat=0`) và không đưa vào thống kê trung vị/IQR chính thức.
3. **Thứ tự chạy xen kẽ (Interleaved Execution):**
   - Bố trí thứ tự chạy xoay vòng xen kẽ giữa các phiên bản để phân tán nhiễu nhiệt độ và hiện tượng trôi hiệu năng:
     - Lượt lẻ (`repeat = 1, 3, 5...`): `V1 → V2 → V3`
     - Lượt chẵn (`repeat = 2, 4, 6...`): `V3 → V2 → V1`
4. **Quy trình chuẩn bị trạng thái Cache (`cold` vs `warm`):**
   - **`cold` (Cold-cache):**
     - Chạy `adb shell pm clear com.adaptivenews.app` trước khi bắt đầu lượt đo để xóa sạch HTTP cache, Coil disk/memory cache và Room DB.
     - **Ngoại lệ cho S5 (Offline):** Sau khi `pm clear`, cần khởi chạy bước nạp sẵn (pre-seed) bài viết mục tiêu (`article_1`) vào danh sách đã lưu (`Saved`) khi còn kết nối mạng, sau đó ngắt mạng thật trước khi đo thao tác mở đọc ngoại tuyến.
   - **`warm` (Warm-cache):**
     - Sau khi chạy một lượt nạp ấm với cùng `variant` để làm đầy HTTP cache và Coil disk cache, đóng tiến trình (`adb shell am force-stop com.adaptivenews.app`) rồi mở lại ứng dụng mà **không** xóa cache đĩa.
5. **Quy tắc cách ly Profiler:**
   - Không gộp lượt chạy có gắn Android Studio Profiler/method trace nặng với lượt chạy đo metric chuẩn.
   - Giữ nguyên các lượt chạy gặp lỗi (`success=false`, crash hoặc ANR) trong bảng `run_metrics.csv` kèm nguyên nhân ở cột `notes`, không tự ý xóa bỏ.

---

## 4. Hợp đồng khởi chạy tự động và Test Tags

Bản dựng `benchmark` của ứng dụng (`com.adaptivenews.app/.ui.MainActivity`) nhận các tham số qua Intent extras (tất cả giá trị chuỗi phải khớp biểu thức chính quy `[A-Za-z0-9_-]{1,64}`):

```bash
adb shell am start -W -n com.adaptivenews.app/.ui.MainActivity \
  --es variant V3 \
  --es run_id S2_V3_cold_r01 \
  --es scenario_id S2 \
  --es commit <git_commit_sha> \
  --es config_hash <config_sha> \
  --es reset_mode cold_cache
```

### Hệ thống Test Tags cố định (`testTagsAsResourceId = true`)

| Màn hình | Test Tag / Resource ID | Nhãn điều hướng / Nút thao tác | Hành vi tự động hóa |
|---|---|---|---|
| **Feed** | `feed_list`, `article_1`, `article_{id}` | Tab `"Tin"` | Cuộn danh sách theo kịch bản, chọn mở bài viết cố định |
| **Search** | `search_input`, `search_results` | Tab `"Tìm"` | Nhập từ khóa fixture `"công nghệ 00001"`, chờ sau debounce 300 ms |
| **Detail** | `detail_body`, `save_button` | Nút `"Lưu bài"` / `"Bỏ lưu"` | Xác nhận hiển thị nội dung chi tiết và lưu bài viết |
| **Saved** | `saved_list` | Tab `"Đã lưu"` | Mở lại `article_1` và kiểm tra `detail_body` khi ngắt mạng thật |
| **Lab** | `lab_screen`, `variant_selector`, `mode_label`, `export_button` | Tab `"Lab"`, nút `"Normal"`, `"Critical"`, `"Slow"`, `"Dùng trạng thái thật"`, `"Reset cache ảnh"`, `"Reset dữ liệu"` | Kiểm tra chế độ hiện tại, tiêm snapshot giả lập (S6), xuất log JSONL |

---

## 5. Đặc tả chi tiết kịch bản S1–S8

| Mã | Tên kịch bản | Trạng thái Cache | Thao tác chuẩn hóa | Chỉ số chính thu thập |
|---|---|---|---|---|
| **S1** | **Startup** | `cold`, `warm` (đo tách nhóm) | Khởi chạy `MainActivity` từ trạng thái `COLD` (tiến trình chưa chạy) và `WARM` (Activity tạo lại), chờ đến khi `feed_list` hiển thị phần tử đầu tiên. | `startup_ms`, `peak_pss_mib` |
| **S2** | **Feed scrolling (Mạng tốt)** | `cold`, `warm` | Mở `feed_list` dưới điều kiện mạng tốt (20 Mbps / 40 ms), thực hiện chuỗi thao tác cuộn xuống/lên định sẵn trong đúng **60 giây** (15 chu kỳ × 4 giây). | `frame_p95_ms`, `jank_pct`, `peak_pss_mib`, `cpu_pct`, `image_p95_ms`, `network_body_bytes`, `cache_hit_pct`, `prefetch_waste_pct` |
| **S3** | **Feed scrolling (Mạng chậm)** | `cold`, `warm` | Lặp lại đúng chuỗi cuộn 60 giây của S2 dưới điều kiện network shaping chậm (1 Mbps / 250 ms). | `image_p95_ms`, `request_p50_ms`, `request_p95_ms`, `network_body_bytes`, `jank_pct`, `prefetch_waste_pct`, `mode_switches` |
| **S4** | **Search + Detail** | `cold`, `warm` | Chuyển sang tab `"Tìm"`, nhập từ khóa cố định `"công nghệ 00001"` vào `search_input`, chờ `search_results` và `article_1` xuất hiện (sau debounce 300 ms), chạm mở `article_1` và chờ `detail_body` hiển thị. | `request_p50_ms`, `request_p95_ms`, `search_ready`, `detail_ready`, `network_body_bytes` |
| **S5** | **Offline Saved Reading** | `warm` (đã seed bài lưu) | Lưu bài `article_1` khi có mạng → ngắt kết nối mạng thật (`svc wifi disable` & `svc data disable`) → mở tab `"Đã lưu"` (`saved_list`) → chạm mở `article_1` → xác minh `detail_body` hiển thị đầy đủ đoạn văn bản chi tiết. | `success` (pass/fail), `detail_ready` latency |
| **S6** | **Context Injection (Simulated)** | `warm` | Mở tab `"Lab"` (`lab_screen`), tiêm chuỗi trạng thái giả lập: `Normal` (15s) → `Critical` (15s, kiểm tra hạ xuống `ECO` tức thời) → `Normal` (35s, kiểm tra hysteresis 3 mẫu và cooldown ≥ 30s) → `Slow` (15s, kiểm tra ràng buộc mạng) → `Normal` (15s). | `context_source=simulated`, `engine_p95_ms`, `mode_switches`, tính đúng đắn của policy (không dùng suy diễn RAM/pin thật) |
| **S7** | **Stress Test (5 phút)** | `cold` | Lặp liên tục trong **300 giây**: cuộn `feed_list` (20s) → chuyển sang `"Tìm"` tìm kiếm theo chu kỳ từ khóa chủ đề → mở `article_{id}` xem `detail_body` → quay lại `"Tin"`. | `peak_pss_mib`, `cpu_pct`, `jank_pct`, `success` (không crash/ANR) |
| **S8** | **Long-running / Pin / Nhiệt / Sync (Mở rộng)** | `warm` | Thực thi trên **thiết bị vật lý thật** qua thời lượng đủ chu kỳ WorkManager (15–60 phút). | `energy_mwh` (nếu có công cụ đo hợp lệ), nhiệt độ, tần suất đồng bộ nền `news-feed-sync` |

---

## 6. Định nghĩa Schema 25 cột `experiments/processed/run_metrics.csv`

Thứ tự 25 cột bắt buộc trong `experiments/processed/run_metrics.csv`:

```csv
run_id,commit,device,api_level,scenario_id,variant,repeat,cache_state,context_source,startup_ms,frame_p95_ms,jank_pct,peak_pss_mib,cpu_pct,request_p50_ms,request_p95_ms,image_p95_ms,network_body_bytes,cache_hit_pct,prefetch_waste_pct,engine_p95_ms,mode_switches,energy_mwh,success,notes
```

### Quy tắc tính toán từng chỉ số từ dữ liệu thô (`experiments/raw/<run_id>/`)

1. **`startup_ms`**: Thời gian khởi động (`timeToInitialDisplayMs` từ Macrobenchmark `StartupTimingMetric` hoặc `TotalTime`/`WaitTime` từ `am start -W`), chỉ ghi cho kịch bản S1, tách rõ `cold` và `warm`.
2. **`frame_p95_ms`**: Phân vị thứ 95 của thời gian CPU dựng khung hình (`frameDurationCpuMs` từ Macrobenchmark `FrameTimingMetric` hoặc `dumpsys gfxinfo`).
3. **`jank_pct`**: Tỷ lệ khung hình bị giật:
   $$\text{jank\_pct} = \frac{\text{janky\_frames}}{\text{total\_frames}} \times 100$$
   Sử dụng cùng một công cụ và ngưỡng frame budget cố định cho mọi phiên bản (lưu tổng số frame trong `metadata.json`).
4. **`peak_pss_mib`**: Giá trị `TOTAL PSS` lớn nhất quan sát được qua các lần lấy mẫu `adb shell dumpsys meminfo com.adaptivenews.app` trong lượt chạy, quy đổi từ KiB sang MiB ($\text{KiB} / 1024.0$).
5. **`cpu_pct`**: Trung bình giá trị `attrs.app_cpu_pct` từ các sự kiện `resource_snapshot` trong `events.jsonl` (đã được chuẩn hóa theo số nhân CPU trong `AndroidResourceMonitor.kt`: $\Delta \text{cpuMs} / \Delta \text{elapsedMs} \times 100 / \text{cores}$).
6. **`request_p50_ms`, `request_p95_ms`**: Phân vị thứ 50 và 95 của trường `value` (ms) trên các sự kiện `request_end` hoàn tất thành công (`status` thuộc `200..299` hoặc `304`).
7. **`image_p95_ms`**: Phân vị thứ 95 của trường `value` (ms) trên các sự kiện `image_visible` (thời gian từ khi bind item đến khi ảnh hiển thị thành công).
8. **`network_body_bytes`**: Tổng `attrs.bytes_body` của toàn bộ sự kiện `request_end` trong lượt chạy (chỉ tính số byte phần thân phản hồi thực nhận từ mạng qua network interceptor, không cộng byte đọc từ cache cục bộ, header hay TLS).
9. **`cache_hit_pct`**:
   $$\text{cache\_hit\_pct} = \frac{\text{số request có cache\_source} \in \{\text{cache}, \text{conditional\_cache}\}}{\text{tổng số request\_end hợp lệ}} \times 100$$
10. **`prefetch_waste_pct`**:
    - Gọi $K_{\text{ok}}$ là tập các khóa `key` từ sự kiện `prefetch_end` có `attrs.success == true` và `value > 0`.
    - Gọi $B(k)$ là số byte (`value`) của khóa $k \in K_{\text{ok}}$, và $K_{\text{used}}$ là tập các khóa xuất hiện trong sự kiện `prefetch_used` trong cùng lượt chạy.
    - Công thức:
      $$\text{prefetch\_waste\_pct} = \frac{\sum_{k \in K_{\text{ok}} \setminus K_{\text{used}}} B(k)}{\sum_{k \in K_{\text{ok}}} B(k)} \times 100$$
    - Nếu mẫu số $\sum_{k \in K_{\text{ok}}} B(k) = 0$ (ví dụ ở `V1` hoặc `V3 Eco`), giá trị để trống (`""`, tương ứng `N/A`), tuyệt đối không điền `0`.
11. **`engine_p95_ms`**: Phân vị thứ 95 của trường `value` (ms) từ các sự kiện `engine_eval`.
12. **`mode_switches`**: Số sự kiện `policy_change` có sự thay đổi chế độ thực sự (`attrs.old_mode != mode`). Các sự kiện `policy_change` giữ nguyên `mode` nhưng thay đổi cấu hình do ràng buộc mạng tức thời được thống kê riêng vào `policy_changes_same_mode` trong báo cáo chi tiết.
13. **`energy_mwh`**: Chỉ ghi khi có thiết bị đo năng lượng hợp lệ ở kịch bản S8; để trống (`""`) trong các trường hợp khác.
14. **`success`**: `true` nếu lượt chạy hoàn tất toàn bộ thao tác kịch bản, có sự kiện `run_start` và không gặp crash/ANR/exception; ngược lại là `false`.

---

## 7. Quy tắc thống kê và tổng hợp (`scripts/aggregate_metrics.py`)

1. **Phân nhóm thống kê:**
   - Nhóm theo khóa `(scenario_id, variant, cache_state, context_source, commit, device, api_level)`.
   - Tuyệt đối không gộp `S2` với `S3`, không gộp `cold` với `warm`, không gộp `simulated` với `real`.
2. **Công thức phân vị và khoảng tứ phân vị (IQR):**
   - Sắp xếp tăng dần $n$ giá trị hợp lệ của chỉ số qua các lượt chạy thành công: $x_0 \le x_1 \le \dots \le x_{n-1}$.
   - Với phân vị $p \in \{0.25, 0.50, 0.75\}$, vị trí nội suy tuyến tính là:
     $$\text{pos} = (n - 1) \times p, \quad i = \lfloor \text{pos} \rfloor, \quad f = \text{pos} - i$$
     $$Q(p) = x_i + f \times (x_{\min(i+1, n-1)} - x_i)$$
   - Trung vị $\text{Median} = Q(0.50)$, $\text{IQR} = Q(0.75) - Q(0.25)$.
   - Khi $n = 1$, giá trị `iqr` được để trống (`""`). Khi $n < 5$, gắn cờ `insufficient_repeats = true`.
3. **Công thức đánh giá mức cải thiện và Guardrails (Mục 4.7 & 11.2):**
   - Với chỉ số càng thấp càng tốt ($M$):
     $$\text{Improvement (\%)} = \frac{M_{\text{baseline}} - M_{\text{optimized}}}{M_{\text{baseline}}} \times 100\% \quad (\text{trả về N/A nếu } M_{\text{baseline}} = 0)$$
   - Với tỷ lệ phần trăm như `jank_pct`, báo cáo thêm độ lệch tuyệt đối theo điểm phần trăm ($\Delta \text{pp} = \text{jank}_{\text{optimized}} - \text{jank}_{\text{baseline}}$).
   - **Ngưỡng nghiệm thu đề xuất (V3 so với V2):**
     - Giảm $\ge 15\%$ `peak_pss_mib` hoặc `network_body_bytes` trong ít nhất một điều kiện tài nguyên bất lợi (S3/S7).
     - Guardrail độ mượt: $\Delta \text{jank\_pct} \le +2.0$ điểm phần trăm.
     - Guardrail độ trễ: mức tăng `request_p95_ms` / `image_p95_ms` / `frame_p95_ms` $\le +10.0\%$.
