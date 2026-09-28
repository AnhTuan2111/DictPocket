<img src="app-desktop/src/main/resources/icon/app-256.png" width="96" align="right" alt="">

# Từ điển & dịch offline Anh–Việt

Ứng dụng desktop tra từ và dịch câu Anh–Việt **chạy hoàn toàn offline**. Không có server,
không có tài khoản, không một dòng mã nào mở kết nối mạng.

| | |
|---|---|
| Mục từ | **109.356** từ 3 nguồn (từ điển 109K + 2 bảng thuật ngữ tự soạn) |
| Tra một từ | **13,7 µs** |
| Dịch một câu 24 từ | **6 ms** |
| RAM lúc chạy | 132–170 MB |
| Yêu cầu | Windows 10/11 64-bit. **Không cần cài Java.** |

---

## 1. Tải và cài

Có **hai bản**, cùng một mã nguồn, khác nhau đúng một chỗ: bản AI có mang theo mô hình
dịch máy nơ-ron.

| Bản | Dịch câu bằng | Kích thước | Nên dùng khi |
|---|---|---|---|
| `TuDienOffline-1.0.0.msi` | Từ điển + luật ngữ pháp. **Không có mô hình AI nào.** | ~46 MB | Tra từ, dịch câu ngắn, máy yếu |
| `TuDienOffline-AI-1.0.0.msi` | Thêm mô hình nơ-ron chạy cục bộ | ~185 MB | Cần dịch câu dài, câu bị động, mệnh đề quan hệ |

Tải ở mục [Releases](https://github.com/AnhTuan2111/offline-translate-vi-en/releases).
Cài được **cả hai song song** — tên ứng dụng khác nhau, icon khác màu.

### Các bước

1. Tải file `.msi` rồi bấm đúp.
2. **Windows sẽ hiện cảnh báo SmartScreen** — bộ cài này *chưa ký số*. Bấm
   **More info** → **Run anyway**. Muốn yên tâm thì đối chiếu mã băm trước khi cài:

   ```powershell
   Get-FileHash .\TuDienOffline-1.0.0.msi -Algorithm SHA256
   ```

   So với `SHA256SUMS.txt` đi kèm bản phát hành. Khớp nghĩa là file bạn tải đúng là file
   đã được dựng ra, không bị sửa dọc đường.
3. Trình cài đặt cho chọn thư mục, tự tạo shortcut ở Start Menu và ngoài Desktop.
4. Gỡ cài đặt bằng **Settings → Apps** như mọi ứng dụng Windows khác.

### Không muốn cài đặt

Bản `.zip` giải nén ra chạy ngay, không đụng vào registry, xoá thư mục là sạch:

```powershell
Expand-Archive TuDienOffline-1.0.0-windows.zip -DestinationPath D:\TuDien
D:\TuDien\TuDienOffline\TuDienOffline.exe
```

---

## 2. Dùng

Ba chế độ, đổi bằng nút trên thanh công cụ hoặc phím tắt:

| Phím tắt | Chế độ | Ví dụ |
|---|---|---|
| `Ctrl+1` | Tra từ Anh→Việt | `give up` → thẻ cụm động từ hiện trước mục từ `give` |
| `Ctrl+2` | Dịch cả câu Anh→Việt | kèm chú giải từng cụm bên dưới để đối chiếu |
| `Ctrl+3` | Tra ngược Việt→Anh | gõ `cham soc` **không dấu** vẫn ra `tend, care, attend` |
| `Ctrl+L` | Về ô nhập | |

Gõ sai chính tả vẫn ra: `aboout` → `about`, `cham sok` → gợi ý `chăm sóc`.

Ở bản AI, ô **"dùng mô hình AI trên máy"** xuất hiện trong chế độ dịch câu. Bản thường
không có ô đó vì không có mô hình nào để bật.

### Thêm từ điển của riêng bạn

Định dạng TSV, mỗi dòng một mục, phân cách bằng **Tab**:

```
từ tiếng Anh	nghĩa tiếng Việt	từ loại
use case	ca sử dụng	danh từ
race condition	lỗi tranh chấp do thứ tự thực thi	danh từ
```

Dòng bắt đầu bằng `#` là chú thích. Cột từ loại có thể bỏ trống.
Dựng lại dữ liệu với nguồn mới (xem §3), nguồn truyền thêm ở dòng lệnh **luôn được ưu tiên
hơn** từ điển nền. Trong app, nút **Nguồn từ điển** cho bật/tắt và đổi thứ tự ngay, không phải
dựng lại.

---

## 3. Chạy từ mã nguồn

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

### Tự đóng gói

```powershell
.\scripts\dong-goi.ps1                          # thư mục chạy ngay, không cần cài
.\scripts\dong-goi.ps1 -Type msi                # bộ cài .msi
.\scripts\dong-goi.ps1 -Type msi -WithNmt       # bộ cài kèm mô hình AI
.\scripts\phat-hanh.ps1 -Version 1.0.0          # cả hai bản + SHA256 + gắn thẻ git
```

`.msi` cần **WiX Toolset 3.x**. Không phải cài vào máy: tải bản portable
[`wix314-binaries.zip`](https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip)
giải nén vào `tools\wix`, script tự thêm vào PATH.

Icon sinh bằng `python scripts\tao-icon.py` (cần Pillow) — chỉ chạy lại khi muốn đổi thiết
kế, file `.ico` đã commit sẵn.

---

## 4. Chuyện offline và chuyện AI

Nói rõ để không ai hiểu nhầm:

- **Bản thường không có mô hình AI nào.** Dịch câu bằng từ điển + bảng xác suất
  (IBM Model 1, học từ kho câu song ngữ) + luật ngữ pháp viết tay. Toàn bộ là bảng tra và
  câu lệnh `if`, xem được từng bước trong phần chú giải.
- **Bản AI có một mô hình nơ-ron thật** chạy trên máy bạn — `opus-mt-en-vi` đã xuất sang
  ONNX, 6 lớp encoder + 6 lớp decoder. Nó chạy offline, nhưng nó vẫn là AI.
- Cả hai bản đều **không mở kết nối mạng**. Mô hình tải về một lần bằng script riêng,
  lúc chạy app thì không có gì đi ra ngoài.

**Giới hạn đã biết của bản thường:** câu bị động kèm mệnh đề quan hệ thì dịch sai hẳn —
`restricts what each user is allowed to do` ra "giới hạn gì mỗi người dùng là cho phép để
cho đến". Đây là trần của hướng dịch bằng luật, thêm từ điển không cứu được. Loại câu đó
cần bản AI.

---

## 5. Cấu trúc

| Module | Phụ thuộc lúc chạy | Việc |
|---|---|---|
| `dict-core` | **không có gì ngoài JDK** | định dạng `dict.pack`, index BM25, tra cứu, dịch bằng luật |
| `dict-importer` | `dict-core` | đọc nguồn từ điển, dựng `.pack` + index, nghiệm thu dữ liệu |
| `nmt-engine` | ONNX Runtime | dịch máy nơ-ron, **tuỳ chọn** — thiếu jar thì app vẫn chạy |
| `app-desktop` | JavaFX | giao diện |

Không dùng Spring Boot: ứng dụng desktop một tiến trình không cần container DI, và
thời gian khởi động thì cần. Composition root viết tay nằm ở `AppContext`.

Từ điển nằm trong một file nhị phân bất biến, đọc bằng `mmap` qua `java.lang.foreign.Arena`
nên đóng là nhả file ngay — trên Windows điều đó quan trọng. Chi tiết định dạng và thuật
toán ở `docs/PLAN.md`.

---

## 6. Nguồn dữ liệu và giấy phép

| Thành phần | Nguồn | Giấy phép |
|---|---|---|
| Mô hình nơ-ron | [Xenova/opus-mt-en-vi](https://huggingface.co/Xenova/opus-mt-en-vi) (Helsinki-NLP) | Apache-2.0 |
| `anhviet109K.txt` | bộ từ điển Anh–Việt lưu hành trên mạng | **không rõ** |
| Bảng thuật ngữ trong `data/` | tự soạn | theo mã nguồn |

Xuất xứ và giấy phép của `anhviet109K.txt` chưa xác minh được. Vì vậy **chưa phát hành rộng
rãi bản dựng sẵn**; dùng cá nhân và học tập. Ai muốn phát hành công khai thì nên thay bằng
nguồn có giấy phép rõ ràng, ví dụ FreeDict `eng-vie` (GPL) — cổng `DictParser` đã chừa sẵn
cho việc đó, thêm một nguồn không phải đụng vào `dict-core`.
