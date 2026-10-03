package com.anhtuan.dict.core.pack;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;

// So sánh chuỗi theo thứ tự byte UTF-8 không dấu. PackWriter sắp xếp KEYS lúc build, PackReader binary
// search lúc tra; hai bên lệch quy tắc so sánh thì tra sai âm thầm (thỉnh thoảng không tìm thấy từ).
// String#compareTo so theo char UTF-16, khác thứ tự byte UTF-8 ở ký tự ngoài BMP, nên cả hai phải dùng class này.
public final class Utf8Compare
{
    private Utf8Compare()
    {
    }

    // Comparator cho PackWriter khi sắp xếp khóa.
    public static final Comparator<String> COMPARATOR = (a, b) -> compare(a.getBytes(StandardCharsets.UTF_8),
            b.getBytes(StandardCharsets.UTF_8));

    public static int compare(byte[] a, byte[] b)
    {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++)
        {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF); // không dấu: byte 0x80+ phải lớn hơn
            if (diff != 0)
                return diff;
        }
        return a.length - b.length;
    }

    // So sánh khóa trong buffer đã mmap (kết thúc bằng 0x00) với khóa cần tìm, đọc trực tiếp không tạo String.
    // Trả về âm nếu khóa trong buf nhỏ hơn target, dương nếu lớn hơn, 0 nếu bằng.
    public static int compareAt(ByteBuffer buf, int keyOffset, byte[] target)
    {
        for (int i = 0; i < target.length; i++)
        {
            int c = buf.get(keyOffset + i) & 0xFF;
            if (c == 0x00)
                return -1; // khóa trong buf ngắn hơn nên nhỏ hơn
            int diff = c - (target[i] & 0xFF);
            if (diff != 0)
                return diff;
        }
        int next = buf.get(keyOffset + target.length) & 0xFF;
        return next == 0x00 ? 0 : 1; // khóa trong buf dài hơn nên lớn hơn
    }
}
