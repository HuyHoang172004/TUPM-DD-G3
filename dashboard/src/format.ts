export function number(value: number | null | undefined, unit = ''): string {
  if (value == null || !Number.isFinite(value)) return '—';
  if (unit === 'boolean') return value ? 'Đạt' : 'Không đạt';
  if (unit === 'bytes') return value >= 1048576 ? `${(value / 1048576).toFixed(2)} MiB` : `${(value / 1024).toFixed(1)} KiB`;
  return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: value < 10 && value !== 0 ? 2 : 1 }).format(value) + (unit && unit !== 'boolean' ? ` ${unit}` : '');
}
export const statusText: Record<string, string> = {
  preparing: 'Đang chuẩn bị', awaiting_capture: 'Chờ video', running: 'Đang đo', saving_video: 'Đang lưu video',
  completed: 'Hoàn tất', failed: 'Thất bại', cancelled: 'Đã dừng', incomplete: 'Thiếu bằng chứng',
};
export const verdictText: Record<string, string> = { not_measured: 'Chưa có số đo', insufficient: 'Chưa đủ bằng chứng', improved: 'Cải thiện rõ', regressed: 'Có đánh đổi / suy giảm' };

export function configLabel(key: string, value: unknown): string {
  const names: Record<string, string> = { paging: 'Phân trang', stable_keys: 'Key & type', memoize_requests: 'Ghi nhớ request', image_width: 'Ảnh', decode_original: 'Decode nguồn', memory_cache: 'Cache ảnh RAM', memory_cache_mib: 'Budget RAM', http_cache: 'HTTP cache', prefetch_count: 'Ảnh tải trước', request_limit: 'Request tối đa', debounce_ms: 'Debounce', distinct_queries: 'Lọc truy vấn lặp', dedup: 'Gộp request trùng', cancel_previous: 'Hủy request cũ', animations: 'Hiệu ứng', sync_minutes: 'Chu kỳ đồng bộ', sync_enabled: 'Đồng bộ nền', adaptive: 'Theo engine', hysteresis: 'Chống dao động', room_index: 'Index Room', variant: 'Phiên bản', native_variant: 'Cấu hình gốc' };
  const units: Record<string, string> = { image_width: 'px', memory_cache_mib: 'MiB', debounce_ms: 'ms', sync_minutes: 'phút' };
  return `${names[key] ?? key}: ${typeof value === 'boolean' ? value ? 'bật' : 'tắt' : String(value)}${units[key] ? ' '+units[key] : ''}`;
}
