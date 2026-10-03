package com.anhtuan.dict.core.pack;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

// LEB128 không dấu: mỗi byte mang 7 bit dữ liệu, bit 0x80 báo còn byte nữa.
// 0..127 tốn 1 byte, 128..16383 tốn 2 byte; nhờ vậy postings chỉ ~1,3 byte mỗi lượt thay vì 4 byte.
public final class VarInt
{
    private VarInt()
    {
    }

    // Số byte cần để mã hóa value.
    public static int sizeOf(int value)
    {
        int n = 1;
        int v = value >>> 7;
        while (v != 0)
        {
            n++;
            v >>>= 7;
        }
        return n;
    }

    public static void write(ByteArrayOutputStream out, int value)
    {
        int v = value;
        while ((v & ~0x7F) != 0)
        {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    // Đọc từ ByteBuffer, con trỏ buffer tự tiến lên.
    public static int read(ByteBuffer buf)
    {
        int result = 0;
        int shift = 0;
        while (true)
        {
            byte b = buf.get();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0)
                return result;
            shift += 7;
            if (shift > 35)
                throw new IllegalStateException("varint too long, corrupt file?");
        }
    }
}
