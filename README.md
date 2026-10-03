<p align="center"><img src="docs/screenshots/banner.png" width="920" alt="DictPocket: từ điển và dịch câu Anh–Việt, dùng hoàn toàn offline"></p>

<p align="center">
<a href="https://github.com/AnhTuan2111/offline-translate-vi-en/releases/latest/download/DictPocket-Setup.msi"><img src="docs/screenshots/btn-download.png" width="430" alt="Tải DictPocket, bản thường 46 MB"></a>
<a href="https://github.com/AnhTuan2111/offline-translate-vi-en/releases/latest/download/DictPocket-AI-Setup.msi"><img src="docs/screenshots/btn-download-ai.png" width="430" alt="Tải DictPocket AI, 185 MB"></a>
</p>

<p align="center">
Hầu hết mọi người nên chọn <b>bản thường</b>. Bản <b>AI</b> thêm bộ dịch máy chạy ngay trên máy bạn, dịch câu dài mượt hơn nhưng lâu hơn nửa giây mỗi câu.<br>
Không muốn cài? <a href="https://github.com/AnhTuan2111/offline-translate-vi-en/releases/latest/download/DictPocket-Portable.zip">Bản chạy thẳng (45 MB)</a>: giải nén, bấm <code>DictPocket.exe</code>, xoá thư mục là sạch.
</p>

> **Đừng bấm nút `Code` màu xanh** hay hai dòng `Source code` ở trang Releases: đó là mã nguồn, không có phần mềm.

![Xem trước](docs/screenshots/h-preview.png)

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/word.png" width="430" alt="Tra cụm give up"><br><sub>Gõ <code>give up</code>: nghĩa của cả cụm hiện lên trước, kèm ví dụ</sub></td>
    <td align="center"><img src="docs/screenshots/sentence.png" width="430" alt="Dịch câu"><br><sub>Dịch cả câu, bên dưới là chú giải từng cụm, bấm ô để đổi nghĩa</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/reverse.png" width="430" alt="Tra ngược Việt sang Anh"><br><sub>Việt → Anh: gõ không dấu <code>cham soc</code> vẫn ra đúng</sub></td>
    <td align="center"><img src="docs/screenshots/suggest.png" width="430" alt="Gợi ý chính tả"><br><sub>Gõ sai chính tả thì có dòng "Ý bạn là", bấm vào là tra luôn</sub></td>
  </tr>
</table>

![Cài đặt](docs/screenshots/h-install.png)

1. Bấm đúp file `.msi` vừa tải. Không cần cài Java, **không cần quyền quản trị**.
2. Nếu Windows hiện bảng xanh **"Windows protected your PC"**: bấm **More info**, rồi **Run anyway**.
   Bảng này hiện vì bộ cài chưa có chữ ký số trả phí, không phải vì phát hiện virus.
3. Bấm **Next** vài lần là xong. Mở bằng shortcut ngoài Desktop hoặc gõ *DictPocket* vào ô tìm kiếm của Windows.

Gỡ cài đặt: **Settings → Apps → Installed apps → DictPocket → Uninstall**.

![Cách dùng](docs/screenshots/h-usage.png)

Bấm nút trên cùng để đổi chế độ, gõ vào ô rồi bấm Enter.

| Chế độ | Làm gì |
|---|---|
| **Tra từ** | Gõ một từ hoặc cụm như `give up`: nghĩa của cả cụm hiện lên trước. Gõ sai chính tả vẫn ra (`aboout` → `about`). |
| **Dịch câu** | Dán cả câu. Dưới bản dịch là chú giải từng cụm; ô nào có `▾` thì bấm vào để chọn nghĩa khác. |
| **Việt → Anh** | Gõ tiếng Việt ra danh sách từ tiếng Anh. Gõ không dấu cũng được. |

Phím tắt: `Ctrl`+`1` / `2` / `3` đổi chế độ, `Ctrl`+`L` về ô nhập.
Nút **Nguồn từ điển** để bật/tắt từng bộ từ điển và đổi thứ tự ưu tiên. Ví dụ đọc tài liệu kỹ thuật thì kéo
"Thuật ngữ CNTT" lên trên: `platform` ra *nền tảng* thay vì *sân ga*.

![Nên biết](docs/screenshots/h-notes.png)

- **Dịch câu không hoàn hảo.** Bản thường dễ sai ở câu bị động hay câu có mệnh đề quan hệ. Hãy coi đó là thứ
  giúp bạn hiểu câu gốc và đối chiếu phần chú giải từng cụm bên dưới. Bản AI khá hơn nhưng vẫn có thể sai.
- Không có phát âm (chỉ có phiên âm IPA). Không dịch cả câu từ Việt sang Anh, chỉ tra từ và cụm.

![Hỏi nhanh](docs/screenshots/h-faq.png)

**Có cần mạng không?** Không, kể cả lần chạy đầu. Phần mềm không có mã nào mở kết nối mạng, bản AI cũng vậy.

**Nên chọn bản nào?** Bản thường, trừ khi hay đọc câu tiếng Anh dài và phức tạp. Cài được cả hai cùng lúc để so.

**Đang dùng bản cũ tên "Từ điển offline" (TuDienOffline)?** DictPocket là ứng dụng riêng nên bản cũ vẫn còn:
gỡ nó trong **Settings → Apps**. Không mất dữ liệu gì.

**Máy yếu chạy được không?** Được: khoảng 150 MB RAM, mở lên chưa đến 2 giây.

![Giấy phép](docs/screenshots/h-license.png)

Miễn phí, dùng cho mục đích cá nhân và học tập. Bộ dịch máy dùng mô hình
[opus-mt-en-vi](https://huggingface.co/Xenova/opus-mt-en-vi) (Helsinki-NLP, Apache-2.0).
Bộ từ điển chung lấy từ một tập dữ liệu Anh–Việt lưu hành trên mạng mà **chưa xác minh được giấy phép**;
nếu bạn giữ bản quyền, mở một [issue](https://github.com/AnhTuan2111/offline-translate-vi-en/issues) là sẽ được gỡ.
