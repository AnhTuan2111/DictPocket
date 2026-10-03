package com.anhtuan.dict.core.spi;

import com.anhtuan.dict.core.model.Segment;
import java.util.List;

// Cổng mở rộng số 2: cho phép cắm NMT vào sau này mà không đập kiến trúc. UI chỉ làm việc với interface này.
// v1 DictionaryGlossEngine trả nhiều Segment (chú giải theo cụm); v2 OnnxNmtEngine trả một Segment kind=TRANSLATED.
public interface TranslationEngine
{

    // Mã engine, dùng trong cấu hình và log.
    String engineId();

    // Tên hiển thị cho người dùng chọn trong UI.
    default String displayName()
    {
        return engineId();
    }

    // Dịch một câu tiếng Anh sang tiếng Việt. Trả về các segment phủ kín chuỗi gốc theo thứ tự offset tăng dần;
    // nối sourceText của tất cả segment lại phải ra đúng chuỗi đầu vào.
    List<Segment> translate(String sentence);

    // Engine đã sẵn sàng chưa (NMT cần nạp model, có thể thất bại).
    default boolean isAvailable()
    {
        return true;
    }
}
