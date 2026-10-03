# Dựng từ mã nguồn

Tài liệu cho lập trình viên. Người dùng phần mềm không cần đọc file này — xem
[README.md](README.md).

Cần **JDK 25** và **Maven 3.9+**.

```powershell
git clone https://github.com/AnhTuan2111/offline-translate-vi-en.git
cd offline-translate-vi-en
.\scripts\run.ps1 -Rebuild
```

`-Rebuild` sinh `data\build\` từ `anhviet109K.txt` (~7 giây). Những lần sau chạy
`.\scripts\run.ps1` là đủ.

| Lệnh | Việc |
|---|---|
| `.\scripts\run.ps1 -Query "give up"` | mở sẵn một truy vấn |
| `.\scripts\run.ps1 -Nmt` | bật sẵn ô dùng mô hình AI |
| `.\scripts\tai-model-nmt.ps1` | tải mô hình nơ-ron (~98 MB) |
| `.\scripts\hoc-bang-xac-suat.ps1` | học lại bảng xác suất dịch từ |
| `mvn test` | 117 test |
| `java -jar dict-importer\target\dict-importer.jar verify data\build` | 38 mục nghiệm thu dữ liệu |

Dựng lại dữ liệu bằng tay, kèm bảng thuật ngữ:

```powershell
java -jar dict-importer\target\dict-importer.jar build `
     anhviet109K.txt data\build data\thuat-ngu-cntt.tsv data\thuat-ngu-y-te.tsv
```

## Thêm từ điển riêng

Định dạng TSV, mỗi dòng một mục, phân cách bằng **Tab**:

```
từ tiếng Anh	nghĩa tiếng Việt	từ loại
use case	ca sử dụng	danh từ
race condition	lỗi tranh chấp do thứ tự thực thi	danh từ
```

Dòng bắt đầu bằng `#` là chú thích, cột từ loại có thể bỏ trống. Truyền thêm file vào lệnh
`build` ở trên; nguồn truyền thêm ở dòng lệnh **luôn được ưu tiên hơn** từ điển nền.

## Cấu trúc

| Module | Phụ thuộc lúc chạy | Việc |
|---|---|---|
| `dict-core` | **không có gì ngoài JDK** | định dạng `dict.pack`, index BM25, tra cứu, dịch bằng luật |
| `dict-importer` | `dict-core` | đọc nguồn từ điển, dựng `.pack` + index, nghiệm thu dữ liệu |
| `nmt-engine` | ONNX Runtime | dịch máy nơ-ron, **tuỳ chọn** — thiếu jar thì app vẫn chạy |
| `app-desktop` | JavaFX | giao diện |

Không dùng Spring Boot: ứng dụng desktop một tiến trình không cần container DI, mà thời
gian khởi động thì cần. Composition root viết tay nằm ở `AppContext`.

Từ điển nằm trong một file nhị phân bất biến, đọc bằng `mmap` qua `java.lang.foreign.Arena`
nên đóng là nhả file ngay — trên Windows điều đó quan trọng. Chi tiết định dạng và thuật
toán ở `docs/PLAN.md`.

**Bản thường không có mô hình AI nào.** Dịch câu bằng từ điển + bảng xác suất (IBM Model 1,
học từ kho câu song ngữ) + luật ngữ pháp viết tay. Toàn bộ là bảng tra và câu lệnh `if`.
**Bản AI** thêm `opus-mt-en-vi` đã xuất sang ONNX, 6 lớp encoder + 6 lớp decoder. Cả hai
đều không mở kết nối mạng.

## Nhánh

| Nhánh | Dùng để |
|---|---|
| `main` | chỉ chứa những gì đã phát hành. Mỗi bản phát hành là một thẻ `vX.Y.Z` trên nhánh này. |
| `develop` | nơi làm việc hằng ngày. Gộp vào `main` khi chuẩn bị phát hành. |

`phat-hanh.ps1` chặn nếu không đứng trên `main` — nó gắn thẻ vào đúng chỗ `HEAD` đang
đứng, chạy nhầm nhánh thì thẻ nằm trên `develop` còn `main` vẫn ở bản cũ.

Phát hành xong, script tự đẩy số phiên bản trong pom lên `X.Y.(Z+1)-SNAPSHOT`. Nhớ gộp
`main` ngược lại `develop`:

```powershell
git switch develop
git merge main
```

## Đóng gói

```powershell
.\scripts\dong-goi.ps1                          # thư mục chạy ngay, không cần cài
.\scripts\dong-goi.ps1 -Type msi                # bộ cài .msi
.\scripts\dong-goi.ps1 -Type msi -WithNmt       # bộ cài kèm mô hình AI
.\scripts\phat-hanh.ps1 -Version 1.0.1          # cả hai bản + .zip + SHA256 + gắn thẻ git
```

`.msi` cần **WiX Toolset 3.x**. Không phải cài vào máy: tải bản portable
[`wix314-binaries.zip`](https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip)
giải nén vào `tools\wix`, script tự thêm vào PATH.

Icon sinh bằng `python scripts\tao-icon.py` (cần Pillow) — chỉ chạy lại khi muốn đổi thiết
kế, file `.ico` đã commit sẵn.

Sau khi phát hành, nhớ sửa số phiên bản trong các link tải ở `README.md` — link dạng
`releases/latest/download/<tên file>` có mang số phiên bản nên sẽ hỏng khi lên bản mới.

## Nguồn dữ liệu và giấy phép

| Thành phần | Nguồn | Giấy phép |
|---|---|---|
| Mô hình nơ-ron | [Xenova/opus-mt-en-vi](https://huggingface.co/Xenova/opus-mt-en-vi) (Helsinki-NLP) | Apache-2.0 |
| `anhviet109K.txt` | bộ từ điển Anh–Việt lưu hành trên mạng | **không rõ** |
| Bảng thuật ngữ trong `data/` | tự soạn | theo mã nguồn |

Xuất xứ và giấy phép của `anhviet109K.txt` **chưa xác minh được**. Bản dựng sẵn phát hành
cho mục đích cá nhân và học tập; nếu người giữ bản quyền dữ liệu có ý kiến thì gỡ xuống ngay.

Dùng vào việc khác thì nên thay bằng nguồn có giấy phép rõ ràng, ví dụ FreeDict `eng-vie`
(GPL). Cổng `DictParser` đã chừa sẵn cho việc đó: thêm một nguồn chỉ là viết thêm một class,
không đụng vào `dict-core`.
