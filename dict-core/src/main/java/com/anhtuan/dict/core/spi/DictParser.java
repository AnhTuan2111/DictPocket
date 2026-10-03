package com.anhtuan.dict.core.spi;

import com.anhtuan.dict.core.model.Entry;
import java.nio.file.Path;
import java.util.stream.Stream;

// Cổng mở rộng số 1: thêm định dạng từ điển mới (StarDict, CSV, TSV, JSON...) chỉ cần viết một class implement interface này,
// không sửa dict-core. Trả Stream chứ không phải List vì file nguồn có thể rất lớn (15 MB / 108k entry), đọc lazy để không giữ hết trong RAM.
public interface DictParser
{

    // Tên định dạng, dùng trong CLI và metadata nguồn; ví dụ "anhviet109k".
    String formatId();

    // Đoán parser này có xử lý được file hay không (dựa vào đuôi file / vài dòng đầu).
    boolean canParse(Path source);

    // Đọc file nguồn thành các Entry. Không được throw khi gặp dòng lỗi định dạng: phải bỏ qua và ghi log;
    // chỉ throw khi không đọc được file. Stream lazy, người gọi phải đóng bằng try-with-resources.
    Stream<Entry> parse(Path source, int sourceId);
}
