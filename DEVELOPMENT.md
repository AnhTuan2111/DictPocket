# Dựng từ mã nguồn

Tài liệu cho lập trình viên. Người dùng phần mềm không cần đọc file này, xem [README.md](README.md).

Cần **JDK 25** và **Maven 3.9+**.

```powershell
git clone https://github.com/AnhTuan2111/offline-translate-vi-en.git
cd offline-translate-vi-en
.\scripts\run.ps1 -Rebuild
```

`-Rebuild` sinh `data\build\` từ `anhviet109K.txt` cùng các nguồn phụ trong `data\*.tsv` (~7 giây).
Những lần sau chạy `.\scripts\run.ps1` là đủ.

| Lệnh | Việc |
|---|---|
| `.\scripts\run.ps1 -Query "give up"` | mở sẵn một truy vấn |
| `.\scripts\run.ps1 -Nmt` | bật sẵn ô dùng mô hình AI |
| `.\scripts\run.ps1 -NoNmt -Query "give up" -Screenshot docs\screenshots\word.png` | chụp cửa sổ ra PNG, chạy như bản thường |
| `.\scripts\download-nmt-model.ps1` | tải mô hình nơ-ron (~98 MB) |
| `.\scripts\train-lexicon.ps1` | học lại bảng xác suất dịch từ |
| `python scripts\generate-icons.py` | sinh lại icon từ lưới pixel (chỉ dùng thư viện chuẩn) |
| `mvn test` | toàn bộ test |
| `java -jar dict-importer\target\dict-importer.jar verify data\build` | nghiệm thu dữ liệu |

## Thêm từ điển riêng

File `.tsv`, mỗi dòng một mục, phân cách bằng **Tab**: `từ tiếng Anh`, `nghĩa tiếng Việt`, `từ loại` (có thể bỏ trống).
Dòng bắt đầu bằng `#` là chú thích; dòng `# name: Tên hiển thị` ở đầu file đặt tên cho nguồn trong hộp thoại.
Đặt file vào `data\` rồi chạy `.\scripts\run.ps1 -Rebuild`. Nguồn xếp trước theo tên file thì được ưu tiên hơn
từ điển nền.

## Cấu trúc

| Module | Phụ thuộc lúc chạy | Việc |
|---|---|---|
| `dict-core` | **không có gì ngoài JDK** | định dạng `dict.pack`, index BM25, tra cứu, dịch bằng luật |
| `dict-importer` | `dict-core` | đọc nguồn từ điển, dựng `.pack` và index, nghiệm thu dữ liệu |
| `nmt-engine` | ONNX Runtime | dịch máy nơ-ron, **tuỳ chọn**: thiếu jar thì app vẫn chạy |
| `app-desktop` | JavaFX (chỉ `javafx-controls`) | giao diện Win9x, khung cửa sổ vẽ tay |

Không dùng Spring Boot: composition root viết tay nằm ở `AppContext`. Từ điển nằm trong một file nhị phân bất biến,
đọc bằng `mmap` qua `java.lang.foreign.Arena` nên đóng là nhả file ngay (quan trọng trên Windows).

## Quy ước

- Ngoặc kiểu Allman (xem `.editorconfig`). Comment là `//` tiếng Việt có dấu. Tên file, tên biến, thông báo lỗi
  và tên test dùng tiếng Anh; chữ hiện cho người dùng trong app là tiếng Việt.
- Nhánh `main` chỉ chứa thứ đã phát hành, mỗi bản là một thẻ `vX.Y.Z`. `develop` là nơi làm việc hằng ngày.

## Đóng gói và phát hành

```powershell
.\scripts\package.ps1                         # thư mục chạy ngay, không cần cài
.\scripts\package.ps1 -Type msi               # bộ cài .msi
.\scripts\package.ps1 -Type msi -WithNmt      # bộ cài kèm mô hình AI
.\scripts\release.ps1 -Version 1.1.0          # cả hai bản + .zip + SHA256 + ghi chú + gắn thẻ git
```

`.msi` cần **WiX Toolset 3.x**: tải bản portable [`wix314-binaries.zip`](https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip),
giải nén vào `tools\wix`, script tự thêm vào PATH.

`release.ps1` đặt tên file phát hành cố định (`DictPocket-Setup.msi`, `DictPocket-AI-Setup.msi`,
`DictPocket-Portable.zip`) nên link tải trong README không phải sửa khi lên bản mới. Hai mã nâng cấp MSI trong
`package.ps1` không được đổi sau khi đã phát hành, vì chính chúng làm bản mới thay thế bản cũ.

Phát hành xong script tự đẩy phiên bản lên `X.Y.(Z+1)-SNAPSHOT`; nhớ gộp `main` ngược lại `develop`.

## Nguồn dữ liệu và giấy phép

| Thành phần | Nguồn | Giấy phép |
|---|---|---|
| Mô hình nơ-ron | [Xenova/opus-mt-en-vi](https://huggingface.co/Xenova/opus-mt-en-vi) (Helsinki-NLP) | Apache-2.0 |
| `anhviet109K.txt` | bộ từ điển Anh–Việt lưu hành trên mạng | **không rõ** |
| Bảng thuật ngữ trong `data/` | tự soạn | theo mã nguồn |

Xuất xứ và giấy phép của `anhviet109K.txt` **chưa xác minh được**. Bản dựng sẵn phát hành cho mục đích cá nhân và
học tập; nếu người giữ bản quyền có ý kiến thì gỡ xuống ngay. Dùng vào việc khác thì nên thay bằng nguồn có giấy phép
rõ ràng, ví dụ FreeDict `eng-vie` (GPL): thêm một nguồn chỉ là viết thêm một class `DictParser`.
