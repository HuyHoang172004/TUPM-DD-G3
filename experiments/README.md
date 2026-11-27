# HƯỚNG DẪN KIỂM THỬ & TÁI LẬP THỰC NGHIỆM — ADAPTIVE NEWS (`experiments/`)

Thư mục `experiments/` lưu trữ giao thức đo đạc, báo cáo kiểm thử hoàn thiện (kèm ảnh chụp màn hình thực tế), dữ liệu đo thô của từng lượt chạy và các bảng kết quả CSV/JSON đã tổng hợp.

---

## 1. Cấu trúc thư mục `experiments/`

```text
experiments/
  README.md                         # Hướng dẫn sử dụng thư mục thực nghiệm (file này)
  protocol.md                       # Giao thức đo đạc chi tiết & công thức 25 cột metric
  BAO_CAO_KIEM_THU_HOAN_THIEN.md    # Báo cáo hoàn thiện các ca kiểm thử (Trước - Sau + Ảnh thật)
  bao_cao_tong_hop_truoc_sau.md     # Bản tóm tắt đối sánh Trước - Sau
  huong_dan_kiem_thu_may_ao.md      # Hướng dẫn từng bước kiểm thử trên máy ảo Android Studio
  report_template.md                # Mẫu hướng dẫn tái lập quy trình đo
  screenshots/                      # 10 ảnh chụp màn hình thực tế từ máy ảo Pixel_9 (emulator-5554)
  raw/                              # Dữ liệu đo thô của từng lượt chạy (<run_id>/metadata.json, events.jsonl, meminfo.txt, gfxinfo.txt)
  processed/
    run_metrics.csv                 # Bảng kết quả chuẩn 25 cột của các lượt chạy
    summary_metrics.csv             # Bảng thống kê trung vị, Q1, Q3, IQR theo nhóm kịch bản
    comparison_report.json          # Kết quả tính % cải thiện RQ1 (V2 vs V1), RQ2 (V3 vs V2) và Guardrails
  fixtures/
    mock_runs/                      # Dữ liệu fixture độc lập (is_mock_fixture=true) cho unit test của pipeline
```

---

## 2. Cách kiểm tra nhanh bộ công cụ phân tích (Không cần bật máy ảo)

Bất kỳ thành viên nào trong nhóm sau khi clone repository có thể chạy ngay bộ kiểm thử đơn vị (Unit Test) để xác nhận bộ parser JSONL và bộ tính toán thống kê CSV hoạt động chính xác:

```powershell
python -m unittest scripts/test_pipeline.py -v
```

Để tổng hợp lại bảng `experiments/processed/run_metrics.csv` từ thư mục dữ liệu thô `experiments/raw/`:

```powershell
python scripts/aggregate_metrics.py --raw-dir experiments/raw --out-dir experiments/processed
```

---

## 3. Cách chạy kiểm thử trực tiếp trên máy ảo Android Studio

Xem hướng dẫn đầy đủ từng bước tại [README.md gốc của dự án](../README.md) hoặc [huong_dan_kiem_thu_may_ao.md](huong_dan_kiem_thu_may_ao.md).
