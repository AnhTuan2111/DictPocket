# Nhật ký thay đổi

Định dạng theo [Keep a Changelog](https://keepachangelog.com/vi/1.1.0/),
đánh số theo [Semantic Versioning](https://semver.org/lang/vi/).

Mỗi bản phát hành có **hai gói**, cùng một mã nguồn:

| Gói | Có gì | Kích thước |
|---|---|---|
| `DictPocket-Setup.msi` | Từ điển + dịch câu bằng luật. **Không có mô hình AI nào.** | ~46 MB |
| `DictPocket-AI-Setup.msi` | Kèm mô hình nơ-ron dịch câu chạy cục bộ | ~185 MB |
| `DictPocket-Portable.zip` | Giải nén là chạy, không cần cài | ~45 MB |

Cả hai đều chạy **hoàn toàn offline** — không có một dòng mã nào mở kết nối mạng.
Tên file không mang số phiên bản, nên link tải trong README luôn đúng với bản mới nhất.

---

## [Chưa phát hành]


## [1.2.0] — 2026-10-05

### Thêm
- **Lịch sử tra.** Nút "Lịch sử" (hoặc `Ctrl+H`) mở danh sách những gì đã tra, mới nhất trước, mỗi mục kèm
  giờ và chế độ: gõ để lọc, bấm đúp hoặc Enter để tra lại, `Delete` hoặc "Xoá mục" để bỏ một mục, "Xoá hết"
  (bấm hai lần cho chắc). Trong ô nhập, phím `↑` `↓` gọi lại câu đã tra như ở dòng lệnh. Lịch sử lưu trên máy
  người dùng, ở `%APPDATA%\DictPocket\history.tsv` (file văn bản, giữ 1.000 mục gần nhất), không gửi đi đâu.

### Sửa
- **Dán đoạn văn dài giờ dịch đủ.** Trước đây bản AI chỉ dịch được vài câu đầu rồi mất dấu chấm
  và bỏ câu sau; nay cả hai bản tách đoạn thành từng câu, dịch từng câu rồi nối lại. Bản thường
  cũng viết hoa đầu mỗi câu. Câu rất dài (trên ~400 ký tự) được cắt thêm ở dấu phẩy thay vì bị
  cắt cụt âm thầm.
- **Dán văn bản có xuống dòng không còn dính chữ.** Ô nhập của JavaFX xoá dấu xuống dòng khi dán,
  nên chữ cuối dòng dính với chữ đầu dòng sau ("the" + "result" thành "theresult"). Nay mỗi chỗ
  xuống dòng thành một dấu cách.
- **Số, đường link và `e.g.` không bị chặt nữa.** Bản thường dịch "3.5 percent" thành "3. 5 phần
  trăm" và làm vỡ đường link; nay giữ nguyên `3.5`, `1,000`, `10:30`, `12/05`, link và `e.g.`/`i.e.`.
- **Bản AI không bịa nữa.** Với đầu vào chỉ có số hoặc dấu câu (`123`, `...`), hoặc là tiếng Việt,
  mô hình từng sinh ra câu thoại phụ đề không liên quan, kèm mã định dạng kiểu `{\3cHFF1000}`. Nay
  những đầu vào đó được trả lại nguyên văn, mã phụ đề và gạch đầu dòng thoại bị loại khỏi kết quả.
- Dán tiếng Việt vào chế độ Dịch câu thì có thông báo thay vì ra chữ vô nghĩa. Tra một từ đơn khi
  đang bật AI thì dùng từ điển (mô hình hay bịa với một từ lẻ).
- Dịch đoạn dài bằng AI hiện tiến độ "câu 12/80", và tra lại giữa chừng thì lượt dịch cũ bị huỷ
  thay vì đè kết quả cũ lên kết quả mới.
- **Ô tích hiện đúng trạng thái.** Dấu tích luôn hiện kể cả khi chưa chọn (ô "Dùng mô hình AI" và
  các ô bật/tắt nguồn trong hộp thoại Nguồn từ điển); chức năng vẫn chạy đúng.


## [1.1.0] — 2026-10-04

### Dành cho người dùng
- **Đổi tên thành DictPocket.** Phần mềm trước đây tên "Từ điển offline Anh - Việt" (file
  `TuDienOffline`).
- **Giao diện mới kiểu Windows 9x**: nền xám, viền nổi, icon pixel vẽ tay, thanh tiêu đề xanh.
  Ba chế độ tra có nút icon lớn; kết quả, ô chú giải, danh sách Việt → Anh và hộp thoại
  Nguồn từ điển đều đổi theo.
- Tên các nguồn từ điển hiện bằng tiếng Việt ("Thuật ngữ CNTT", "Thuật ngữ y tế") thay vì
  tên file.
- Bộ cài có **mã nâng cấp cố định**: từ bản sau, cài đè lên bản cũ sẽ thay thế chứ không
  cài thêm một bản nữa.

> **Đang dùng bản cũ (TuDienOffline)?** DictPocket là một ứng dụng riêng, cài xong bản cũ
> vẫn còn. Muốn gỡ: **Settings → Apps → Installed apps → TuDienOffline → Uninstall**. Từ điển
> nằm trong thư mục cài nên không có dữ liệu cá nhân nào bị mất.

### Cho lập trình viên
- Cửa sổ vẽ tay (thanh tiêu đề, nút thu nhỏ / phóng to / đóng, đổi cỡ) thay cho khung hệ
  điều hành. Vẫn chỉ phụ thuộc `javafx-controls`; toàn bộ phần icon nặng chưa tới 12 KB.
- Toàn bộ mã nguồn chuyển sang kiểu ngoặc Allman; thêm `.editorconfig` cho IntelliJ.
- Comment tinh gọn lại thành `//` tiếng Việt có dấu; thông báo lỗi, tên test, tên file,
  script và tên hằng đều bằng tiếng Anh.
- Đổi tên script: `package.ps1`, `release.ps1`, `download-nmt-model.ps1`, `train-lexicon.ps1`,
  `generate-icons.py` (không còn cần Pillow).
- Tên file phát hành cố định (`DictPocket-Setup.msi`...) để link tải không hỏng mỗi lần lên bản.
- `release.ps1` chặn nếu không đứng trên nhánh `main` (bỏ qua bằng `-AllowAnyBranch`).
  Script gắn thẻ vào đúng chỗ `HEAD` đang đứng, nên chạy nhầm nhánh thì thẻ nằm trên
  `develop` còn `main` vẫn ở bản cũ — đã vấp đúng vậy khi phát hành `v1.0.1`.
- `run.ps1 -Rebuild` nạp luôn các nguồn phụ trong `data\*.tsv`; thêm `-Screenshot` để chụp
  cửa sổ ra PNG (dùng cho ảnh trong README) và `-NoNmt` để chạy như bản thường.
- Một nguồn `.tsv` khai báo tên hiển thị bằng dòng `# name: ...` đầu file.


## [1.0.1] — 2026-10-03

### Sửa
Bốn lỗi phát hiện khi cài thử `v1.0.0` lên một máy Windows thật, không lỗi nào lộ ra khi
chạy từ mã nguồn:

- **Bộ cài đòi quyền administrator.** `msiexec` dừng ở `Error 1925 — You do not have
  sufficient privileges to complete this installation for all users of the machine`. Mặc
  định của `jpackage` là cài cho cả máy. Thêm `--win-per-user-install`: nay cài vào
  `%LOCALAPPDATA%` trong 3,0 giây, không cần admin. Đây là lỗi nặng nhất vì người dùng
  chính của ứng dụng là sinh viên dùng máy trường, phần lớn không có quyền đó.
- **Bản đóng gói đọc nhầm dữ liệu.** `DataLocator` xét `./data/build` và `../data/build`
  — tính theo *thư mục hiện hành* — **trước** thư mục dữ liệu đi kèm bản cài. Mở ứng dụng
  từ một cửa sổ dòng lệnh đang đứng ở cây mã nguồn thì nó đọc dữ liệu của cây mã nguồn,
  mà thanh trạng thái vẫn báo bình thường. Nay dữ liệu đi kèm được xét trước.
- **Bản phát hành ghi sai số phiên bản.** Thanh tiêu đề của `v1.0.0` hiện
  `v1.0.1-SNAPSHOT`. Tên file jar có mang số phiên bản, nên đổi phiên bản rồi build lại mà
  không `clean` thì `target\` còn cả jar cũ lẫn jar mới, và `Copy-Item` với wildcard lấy
  nhầm jar cũ. Thêm `clean`, và thêm hàm `Copy-OneJar` dừng hẳn khi wildcard khớp nhiều
  hơn một file — lần sau lỗi này sẽ kêu to thay vì im lặng.
- **Shortcut nằm trong thư mục Start Menu tên "Unknown"** — mặc định của `jpackage` khi
  không khai báo `--win-menu-group`. Nay là "Tu dien offline".

### Thay đổi
- `dict-importer/dependency-reduced-pom.xml` do `maven-shade-plugin` sinh ra, không theo
  dõi trong git nữa: nó đổi theo số phiên bản nên commit phát hành nào cũng dính nó.


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

[Chưa phát hành]: https://github.com/AnhTuan2111/DictPocket/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/AnhTuan2111/DictPocket/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/AnhTuan2111/DictPocket/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/AnhTuan2111/DictPocket/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/AnhTuan2111/DictPocket/releases/tag/v1.0.0
