"""One registry drives the UI, validated run inputs and benchmark configuration."""
from copy import deepcopy

METRICS = {
    "peak_pss_mib": ("Peak PSS", "MiB", "lower"),
    "network_body_bytes": ("Body từ mạng", "bytes", "lower"),
    "feed_ready_ms": ("Feed sẵn sàng", "ms", "lower"),
    "frame_p95_ms": ("Frame CPU p95", "ms", "lower"),
    "frame_overrun_p95_ms": ("Frame overrun p95", "ms", "lower"),
    "jank_pct": ("Jank", "%", "lower"),
    "cpu_pct": ("CPU / tổng core", "%", "lower"),
    "image_p95_ms": ("Ảnh sẵn sàng p95", "ms", "lower"),
    "memory_hit_pct": ("Memory cache hit", "%", "higher"),
    "http_hit_pct": ("HTTP cache hit", "%", "higher"),
    "requests": ("Request mạng", "requests", "lower"),
    "conditional_requests": ("HTTP 304", "requests", "neutral"),
    "final_search_ms": ("Truy vấn cuối sẵn sàng", "ms", "lower"),
    "search_p95_ms": ("Search p95 (pilot cũ)", "ms", "lower"),
    "detail_p95_ms": ("Detail data p95", "ms", "lower"),
    "prefetch_use_pct": ("Prefetch được dùng", "%", "higher"),
    "prefetch_waste_pct": ("Byte prefetch lãng phí", "%", "lower"),
    "max_active_requests": ("Request hoạt động tối đa", "requests", "neutral"),
    "errors": ("Request lỗi", "requests", "lower"),
    "cancelled_requests": ("Request được hủy", "requests", "neutral"),
    "query_p95_ms": ("Room query p95", "ms", "lower"),
    "mode_switches": ("Lần đổi mode", "switches", "lower"),
    "engine_p95_ms": ("Engine evaluate p95", "ms", "lower"),
    "critical_reaction_ms": ("Phản ứng Critical", "ms", "lower"),
    "recovery_ms": ("Phục hồi sau Critical", "ms", "neutral"),
    "recompositions": ("Item composition", "compositions", "lower"),
    "worker_runs": ("Worker đã chạy", "runs", "neutral"),
    "scheduled_interval_minutes": ("Chu kỳ thực đã lên lịch", "phút", "neutral"),
    "unique_active_work": ("Lịch nền đang hoạt động", "jobs", "neutral"),
    "startup_ms": ("Hiển thị đầu tiên", "ms", "lower"),
    "consumer_successes": ("Consumer thành công", "consumers", "higher"),
    "dedup_network_requests": ("Request tới cùng ID", "requests", "lower"),
    "dedup_body_bytes": ("Body của cùng ID", "bytes", "lower"),
    "offline_success": ("Đọc Saved thành công", "boolean", "higher"),
}


def case(id, title, a, b, journey="scroll", note=""):
    return dict(id=id, title=title, a=a, b=b, journey=journey, note=note)


def method(id, title, group, description, metrics, cases, limitation="", enabled=True):
    return dict(id=id, title=title, group=group, description=description,
                metrics=metrics.split(), cases=cases, limitation=limitation, enabled=enabled)


METHODS = [
    method("paging", "Paging 3", "Dữ liệu", "Tải theo trang thay vì giữ toàn bộ 10.000 summary.",
           "peak_pss_mib network_body_bytes feed_ready_ms frame_p95_ms",
           [case("default", "Eager / Paging 20", {"paging": False}, {"paging": True})]),
    method("stable_keys", "Stable keys & contentType", "Giao diện", "Giữ LazyColumn ở cả hai phía; chỉ thay cách nhận diện item.",
           "frame_p95_ms jank_pct recompositions",
           [case("default", "Không khai báo / Có key và type", {"stable_keys": False}, {"stable_keys": True}, "refresh")]),
    method("recomposition", "Giảm recomposition", "Giao diện", "So sánh việc ghi nhớ ImageRequest, không tạo thêm công việc giả.",
           "recompositions frame_p95_ms cpu_pct",
           [case("default", "Tạo request / remember request", {"memoize_requests": False}, {"memoize_requests": True})],
           "Bài này đánh giá remember ImageRequest; không đại diện mọi kỹ thuật tách state."),
    method("image_size", "Kích thước & decode ảnh", "Hình ảnh", "Tách resize phía server khỏi downsampling trên Android.",
           "network_body_bytes image_p95_ms peak_pss_mib",
           [case("width720", "1080 px / 720 px", {"image_width": 1080}, {"image_width": 720}),
            case("width360", "1080 px / 360 px", {"image_width": 1080}, {"image_width": 360}),
            case("decode", "Decode nguồn / Theo vùng hiển thị", {"decode_original": True}, {"decode_original": False})],
           "Cùng nội dung WebP. Không kết luận riêng về codec; đối chiếu ảnh chụp UI."),
    method("memory_cache", "Cache ảnh RAM", "Hình ảnh", "Đi qua cùng nhóm bài rồi cuộn ngược để kiểm tra tái sử dụng ảnh.",
           "memory_hit_pct requests image_p95_ms peak_pss_mib",
           [case("budget32", "Tắt / 32 MiB", {"memory_cache": False, "http_cache": False}, {"memory_cache": True, "memory_cache_mib": 32, "http_cache": False}, "revisit"),
            case("budget16", "16 MiB / 64 MiB", {"memory_cache": True, "memory_cache_mib": 16, "http_cache": False}, {"memory_cache": True, "memory_cache_mib": 64, "http_cache": False}, "revisit")],
           "Budget cache không phải RAM toàn process. Coil disk cache tắt ở cả hai nhánh."),
    method("http_cache", "HTTP cache & ETag", "Mạng", "Tách cache ảnh còn TTL khỏi phản hồi API được xác thực bằng ETag.",
           "network_body_bytes requests conditional_requests http_hit_pct",
           [case("images", "Ảnh tải lại", {"http_cache": False}, {"http_cache": True}, "revisit"),
            case("etag", "API revalidation", {"http_cache": False}, {"http_cache": True}, "etag")]),
    method("debounce", "Debounce & distinct", "Mạng", "Nhập từng ký tự, cùng truy vấn cuối và cùng kết quả.",
           "requests network_body_bytes final_search_ms",
           [case("debounce", "0 ms / 300 ms", {"debounce_ms": 0}, {"debounce_ms": 300}, "search"),
            case("distinct", "Truy vấn tương đương sau trim", {"distinct_queries": False}, {"distinct_queries": True}, "distinct")]),
    method("dedup", "Request deduplication", "Mạng", "10 consumer đồng thời đọc một ID chưa lưu, cache tắt.",
           "dedup_network_requests dedup_body_bytes consumer_successes detail_p95_ms",
           [case("default", "10 request / Chia sẻ request", {"dedup": False}, {"dedup": True}, "dedup")],
           "Workload đồng thời có kiểm soát trong app, không giả định 10 lần chạm là 10 consumer."),
    method("prefetch", "Prefetch có giới hạn", "Hình ảnh", "Đánh giá cả tiếp tục cuộn lẫn đổi hướng sớm.",
           "image_p95_ms prefetch_use_pct prefetch_waste_pct network_body_bytes",
           [case("forward", "Cuộn tiếp", {"prefetch_count": 0, "http_cache": True}, {"prefetch_count": 4, "http_cache": True}),
            case("reverse", "Đổi hướng", {"prefetch_count": 0, "http_cache": True}, {"prefetch_count": 4, "http_cache": True}, "revisit")]),
    method("concurrency", "Giới hạn concurrency", "Mạng", "Tải ảnh đồng thời và xen yêu cầu detail; giữ transport giống nhau.",
           "max_active_requests detail_p95_ms errors jank_pct",
           [case("cap4", "Không gate / Tối đa 4", {"request_limit": 64}, {"request_limit": 4}, "concurrency"),
            case("cap2", "Tối đa 6 / Tối đa 2", {"request_limit": 6}, {"request_limit": 2}, "concurrency")]),
    method("cancellation", "Coroutine & cancellation", "Dữ liệu", "Thay truy vấn khi request cũ còn chạy; không đưa I/O lên main.",
           "cancelled_requests network_body_bytes final_search_ms errors",
           [case("default", "Hoàn tất cũ / Hủy cũ", {"cancel_previous": False, "debounce_ms": 0, "paging": False}, {"cancel_previous": True, "debounce_ms": 0, "paging": False}, "search")]),
    method("room", "Room index & offline", "Dữ liệu", "Đo query trên DB riêng; kiểm tra Saved không cần truy cập backend.",
           "query_p95_ms offline_success requests",
           [case("index", "Không index / Có index", {"room_index": False}, {"room_index": True}, "room"),
            case("offline", "Đọc bài đã lưu", {}, {}, "offline", "Kiểm tra chức năng, không kết luận A/B cải thiện.")]),
    method("animations", "Hiệu ứng thích ứng", "Giao diện", "Bật/tắt crossfade và hiệu ứng thực có trong ứng dụng.",
           "frame_p95_ms jank_pct cpu_pct",
           [case("default", "Bật / Tắt hiệu ứng", {"animations": True}, {"animations": False})]),
    method("workmanager", "WorkManager", "Điều phối", "Xác minh lịch duy nhất và đo quan sát nền theo thời gian thực.",
           "scheduled_interval_minutes unique_active_work worker_runs network_body_bytes",
           [case("schedule", "Xác minh 15 / 60 phút", {"sync_minutes": 15, "sync_enabled": True}, {"sync_minutes": 60, "sync_enabled": True}, "schedule"),
            case("long", "Quan sát 3 giờ mỗi nhánh", {"sync_minutes": 15, "sync_enabled": True}, {"sync_minutes": 60, "sync_enabled": True}, "background")],
           "Demo kiểm tra cấu hình lịch, không rút ngắn thời gian để suy ra tiết kiệm pin. Năng lượng: N/A trên emulator."),
    method("adaptive", "Adaptive profiles", "Điều phối", "Cấu hình cố định V2 so với engine V3 trên cùng hành trình.",
           "peak_pss_mib network_body_bytes image_p95_ms engine_p95_ms",
           [case("network", "Ngữ cảnh mạng thực", {"variant": "V2", "adaptive": True, "http_cache": True, "memory_cache": True}, {"variant": "V3", "adaptive": True, "http_cache": True, "memory_cache": True}),
            case("injected", "Normal → Critical → Normal", {"variant": "V2", "adaptive": True, "http_cache": True, "memory_cache": True}, {"variant": "V3", "adaptive": True, "http_cache": True, "memory_cache": True}, "context")],
           "Bài tiêm snapshot kiểm tra policy, không giả lập áp lực phần cứng thực."),
    method("hysteresis", "Hysteresis & cooldown", "Điều phối", "Đối chiếu cùng chuỗi snapshot cách nhau 5 giây.",
           "mode_switches critical_reaction_ms recovery_ms engine_p95_ms",
           [case("default", "Chọn trực tiếp / Chống dao động", {"variant": "V3", "adaptive": True, "hysteresis": False, "http_cache": True, "memory_cache": True}, {"variant": "V3", "adaptive": True, "hysteresis": True, "http_cache": True, "memory_cache": True}, "hysteresis")]),
    method("overall", "Tổng thể V1 / V2 / V3", "Tổng hợp", "So sánh gói kỹ thuật; không dùng để quy đóng góp riêng từng phương pháp.",
           "startup_ms peak_pss_mib frame_p95_ms network_body_bytes",
           [case("v1v2", "V1 / V2", {"variant": "V1", "native_variant": True}, {"variant": "V2", "native_variant": True}, "overall"),
            case("v2v3", "V2 / V3", {"variant": "V2", "native_variant": True}, {"variant": "V3", "native_variant": True}, "overall")]),
    method("lazy_init", "Lazy initialization", "Mở rộng", "Chưa có triển khai bật/tắt độc lập trong app.",
           "startup_ms", [], "Chưa triển khai. Không tạo kết quả hoặc nút đo giả.", False),
]

BASE = dict(protocol=2, variant="V2", paging=True, stable_keys=True, memoize_requests=True,
            image_width=720, decode_original=False, memory_cache=False, memory_cache_mib=32,
            http_cache=False, prefetch_count=0, request_limit=4, debounce_ms=300,
            distinct_queries=True, dedup=True, cancel_previous=True, animations=False,
            sync_enabled=False, sync_minutes=30, adaptive=False, hysteresis=True,
            room_index=True, native_variant=False, base_url="http://10.0.2.2:8001/")


def lookup(method_id, case_id):
    m = next((x for x in METHODS if x["id"] == method_id and x["enabled"]), None)
    if not m:
        raise ValueError("Phương pháp chưa sẵn sàng hoặc không tồn tại")
    c = next((x for x in m["cases"] if x["id"] == case_id), None)
    if not c:
        raise ValueError("Kịch bản không hợp lệ")
    return m, c


def configuration(method_id, case_id, arm):
    if arm not in ("A", "B"):
        raise ValueError("Nhánh không hợp lệ")
    _, c = lookup(method_id, case_id)
    result = deepcopy(BASE)
    result.update(c[arm.lower()])
    return result


def public_catalog():
    return {"methods": METHODS, "metrics": {k: dict(label=v[0], unit=v[1], direction=v[2]) for k, v in METRICS.items()}}
