# Nhật ký thay đổi

Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/),
đánh số theo [Semantic Versioning](https://semver.org/lang/vi/).

Mỗi bản phát hành có **hai gói**, cùng một mã nguồn:

| Gói | Có gì | Kích thước |
|---|---|---|
| `TuDienOffline-<ver>.msi` | Từ điển + dịch câu bằng luật. **Không có mô hình AI nào.** | ~46 MB |
| `TuDienOffline-AI-<ver>.msi` | Kèm mô hình nơ-ron dịch câu chạy cục bộ | ~185 MB |

Cả hai đều chạy **hoàn toàn offline** — không có một dòng mã nào mở kết nối mạng.

---

## [Chưa phát hành]

## [1.0.0] — 2026-09-28

Bản phát hành đầu tiên.

### Tra cứu
- Tra từ Anh→Việt: 109.356 mục từ, 122.078 khoá (gồm cả cụm động từ như `give up`
  vốn không phải mục từ riêng trong nguồn). Tra một từ **13,7 µs**.
- Tra cụm thì đẩy thẻ cụm lên đầu, không bắt người dùng tự tìm giữa mục từ dài.
- Tìm Việt→Anh xếp theo độ liên quan; gõ **không dấu** vẫn ra đúng kết quả.
- Gõ sai chính tả: tiếng Anh đoán bằng trigram, tiếng Việt gợi ý "Ý bạn là…?".

### Dịch câu — ba mức
- **Chú giải theo cụm**: mỗi cụm một ô, bấm để đổi nghĩa.
- **Dịch bằng luật**: chọn nghĩa theo từ loại, sắp lại trật tự tiếng Việt, thêm dấu hiệu
  thì. Kèm bảng xác suất dịch từ học từ 1,2 triệu cặp câu song ngữ để chọn đúng nghĩa
  (`government` → "chính phủ" chứ không phải "sự cai trị"). **2 ms** mỗi câu.
- **Dịch bằng mô hình nơ-ron** *(chỉ có trong gói AI)*: opus-mt-en-vi chạy cục bộ,
  **150–450 ms** mỗi câu. Giao diện ghi rõ đây là AI chạy trên máy người dùng.

### Chất lượng dịch — từ điển thuật ngữ và luật ngữ pháp

- Từ điển thuật ngữ CNTT tự soạn: **463 mục**, xếp theo nhóm (quy trình phát triển, UML,
  mẫu thiết kế, giải thuật, CSDL, web/API, DevOps, bảo mật, kiểm thử, AI, động từ hay gặp
  trong đề bài, cụm kỹ thuật hay dịch sai). Tách từ điển thuật ngữ y tế ra file riêng
  (**39 mục**) để bật/tắt độc lập.
- Năm luật đoán từ loại mới, đo trên tài liệu kỹ thuật thật:
  câu mệnh lệnh đầu câu (`Map the four stages…` → "Đối chiếu", không phải "Bản đồ");
  liệt kê động từ sau dấu phẩy và `and` (`to maintain, test, and scale` → "kiểm thử",
  "mở rộng quy mô", không phải "vỏ", "sự chia độ");
  cụm nhiều từ mang từ loại danh từ cũng tham gia sắp lại danh ngữ
  (`a suitable design pattern` → "mẫu thiết kế phù hợp");
  phân từ hiện tại làm giới từ (`including preconditions` → "gồm cả điều kiện tiên quyết");
  tính từ/trạng từ không tính là từ dẫn dắt khi tìm chủ ngữ (`into smaller modules makes it…`).
- Cấp so sánh: `smaller` → "nhỏ hơn", `easier` → "dễ hơn". Trước đây `Lemmatizer` cắt đuôi
  `-er`/`-est` để tra được từ điển nên mất hẳn ý so sánh.
- Số đếm vào bảng hư từ (`four`…`billion`). Thiếu chúng thì `four` bị tra nguồn 109K và ra
  nghĩa cổ "chứng khoán lãi 4 qịu (sử học) bốn xu rượu", lại còn đứng sai chỗ.

- `build` không còn ghi đè bảng ưu tiên nguồn về `priority = id`: bảng thuật ngữ truyền thêm
  ở dòng lệnh luôn đứng **trên** từ điển nền. Trước đây phải sửa tay `sources.tsv` sau mỗi
  lần dựng lại, quên là toàn bộ bảng thuật ngữ thành vô nghĩa.

### Nguồn từ điển
- Thêm được nguồn khác dưới dạng bảng TSV hai cột; bật/tắt và đổi thứ tự ưu tiên ngay
  trong ứng dụng, không phải sinh lại dữ liệu.

### Đóng gói
- Bộ cài `.msi` cho Windows, không cần cài sẵn Java.
- RAM khi chạy 132–170 MB.
- Icon riêng cho từng bản: xanh cho bản thường, xanh lá cho bản AI — cài song song hai bản
  thì nhìn thanh taskbar là biết đang mở bản nào. Sinh bằng `scripts/tao-icon.py`.
- `README.md` hướng dẫn cài đặt, kèm cách đối chiếu SHA256 vì bộ cài **chưa ký số** nên
  Windows SmartScreen sẽ cảnh báo.

### Ghi chú kỹ thuật
- Dữ liệu 14,3 MB: pack nén theo khối + chỉ mục ngược tự viết, không dùng SQLite hay Lucene.
- Toàn bộ `dict-core` không có một dependency nào ngoài JDK.
- 117 test + 38 tiêu chí nghiệm thu tự động trên dữ liệu thật.

### Đã bỏ giữa chừng *(ghi lại để khỏi làm lại)*
- **Tra nhanh từ clipboard + biểu tượng khay**: làm xong rồi gỡ theo quyết định của chủ dự án.
- **Nhét từ ghép tiếng Việt vào chỉ mục**: làm xong, đo, thấy chỉ mục phình 47% mà thứ tự
  kết quả gần như không đổi → bỏ. Danh sách từ ghép chuyển sang dùng cho gợi ý chính tả.

[Chưa phát hành]: https://github.com/AnhTuan2111/offline-translate-vi-en/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/AnhTuan2111/offline-translate-vi-en/releases/tag/v1.0.0
