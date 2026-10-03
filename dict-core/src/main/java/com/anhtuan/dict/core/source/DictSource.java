package com.anhtuan.dict.core.source;

// Một nguồn từ điển đã được đóng gói vào dữ liệu.
// id chính là sourceId ghi trong từng Entry của pack, không được đổi sau khi build vì entry sẽ trỏ sai nguồn.
// format là mã định dạng đã dùng để đọc (DictParser#formatId); file chỉ để người dùng nhận ra nguồn gốc.
// enabled đổi được ngay lúc chạy; priority nhỏ hơn thì ưu tiên hơn, quyết định nguồn nào đưa nghĩa lên trước.
public record DictSource(int id, String name, String format, String file, int entries, boolean enabled, int priority)
{

    public DictSource
    {
        if (name == null || name.isBlank())
            throw new IllegalArgumentException("source name is required");
    }

    public DictSource withEnabled(boolean value)
    {
        return new DictSource(id, name, format, file, entries, value, priority);
    }

    public DictSource withPriority(int value)
    {
        return new DictSource(id, name, format, file, entries, enabled, value);
    }
}
