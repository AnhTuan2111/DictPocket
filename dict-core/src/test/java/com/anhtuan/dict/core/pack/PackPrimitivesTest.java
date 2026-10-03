package com.anhtuan.dict.core.pack;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

// Test các nguyên thủy của pack format: nhỏ nhưng sai là hỏng toàn bộ file.
class PackPrimitivesTest
{

    @Test
    @DisplayName("VarInt: write then read returns the original value")
    void varIntRoundTrip()
    {
        int[] samples = {0, 1, 63, 127, 128, 255, 16_383, 16_384, 1_000_000, Integer.MAX_VALUE};
        for (int v : samples)
        {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            VarInt.write(out, v);
            byte[] bytes = out.toByteArray();
            assertEquals(VarInt.sizeOf(v), bytes.length, "wrong sizeOf for " + v);
            assertEquals(v, VarInt.read(ByteBuffer.wrap(bytes)), "wrong read-back for " + v);
        }
    }

    @Test
    @DisplayName("VarInt: small numbers take few bytes")
    void varIntIsCompactForSmallNumbers()
    {
        assertEquals(1, VarInt.sizeOf(0));
        assertEquals(1, VarInt.sizeOf(127));
        assertEquals(2, VarInt.sizeOf(128));
        assertEquals(2, VarInt.sizeOf(16_383));
        assertEquals(3, VarInt.sizeOf(16_384));
    }

    @Test
    @DisplayName("VarInt: reads consecutive numbers from one buffer")
    void varIntSequential()
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] values = {5, 300, 1, 70_000, 0};
        for (int v : values)
            VarInt.write(out, v);

        ByteBuffer buf = ByteBuffer.wrap(out.toByteArray());
        for (int v : values)
            assertEquals(v, VarInt.read(buf));
    }

    @Test
    @DisplayName("Utf8Compare: bytes 0x80+ are treated as unsigned")
    void utf8CompareTreatsBytesAsUnsigned()
    {
        byte[] ascii = "z".getBytes(StandardCharsets.UTF_8); // 0x7A
        byte[] vietnamese = "ă".getBytes(StandardCharsets.UTF_8); // 0xC4 0x83
        // Nếu so sánh byte có dấu thì 0xC4 thành -60 và "ă" bị coi là nhỏ hơn "z", sai thứ tự.
        assertTrue(Utf8Compare.compare(ascii, vietnamese) < 0,
                "bytes must compare as unsigned or binary search will miss");
    }

    @Test
    @DisplayName("Utf8Compare: a shorter prefix sorts first")
    void utf8ComparePrefixOrdering()
    {
        assertTrue(Utf8Compare.COMPARATOR.compare("about", "about to") < 0);
        assertTrue(Utf8Compare.COMPARATOR.compare("about to", "about") > 0);
        assertEquals(0, Utf8Compare.COMPARATOR.compare("about", "about"));
    }

    @Test
    @DisplayName("compareAt on the buffer agrees with the COMPARATOR used for sorting")
    void compareAtMatchesComparatorUsedForSorting()
    {
        // Rủi ro chính: PackWriter sắp xếp bằng một quy tắc còn PackReader binary search bằng quy tắc khác thì kết quả
        // sai âm thầm.
        List<String> keys = new ArrayList<>(List.of("a", "about", "about to", "ăn", "zebra", "give up", "a la carte",
                "Đông", "đông", "test", "tết", "cafe", "café"));
        keys.sort(Utf8Compare.COMPARATOR);

        // Dựng KEYS y hệt PackWriter: nối liền, ngăn bằng 0x00.
        ByteArrayOutputStream keyBlob = new ByteArrayOutputStream();
        int[] offsets = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++)
        {
            offsets[i] = keyBlob.size();
            keyBlob.writeBytes(keys.get(i).getBytes(StandardCharsets.UTF_8));
            keyBlob.write(0);
        }
        ByteBuffer buf = ByteBuffer.wrap(keyBlob.toByteArray());

        // Mỗi khóa phải tìm thấy chính nó bằng binary search.
        for (String key : keys)
        {
            assertEquals(key, binarySearch(buf, offsets, keys, key), "binary search missed key: " + key);
        }
        // Khóa không tồn tại phải trả null.
        assertNull(binarySearch(buf, offsets, keys, "khongtontai"));
        assertNull(binarySearch(buf, offsets, keys, "abou"));
    }

    @Test
    @DisplayName("BlockCodec: compress then decompress returns the original data")
    void blockCodecRoundTrip()
    {
        Random rnd = new Random(42);
        for (int size : new int[]{1, 100, 8192, 50_000})
        {
            byte[] raw = new byte[size];
            rnd.nextBytes(raw);
            byte[] compressed = BlockCodec.compress(raw);
            assertArrayEquals(raw, BlockCodec.decompress(compressed, raw.length), "round-trip failed for size=" + size);
        }
    }

    @Test
    @DisplayName("BlockCodec: dictionary text compresses at least 3x")
    void blockCodecCompressesDictionaryTextWell()
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++)
        {
            sb.append("@word").append(i).append(" /wə:d/\n").append("*  danh từ\n- nghĩa thứ nhất của từ này\n")
                    .append("=this is an example+ đây là một ví dụ\n");
        }
        byte[] raw = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] compressed = BlockCodec.compress(raw);

        double ratio = (double) raw.length / compressed.length;
        assertTrue(ratio > 3.0, "compression ratio only " + String.format("%.1f", ratio) + "x, expected > 3x");
        assertArrayEquals(raw, BlockCodec.decompress(compressed, raw.length));
    }

    // Mô phỏng đúng thuật toán PackReader dùng.
    private static String binarySearch(ByteBuffer buf, int[] offsets, List<String> keys, String target)
    {
        byte[] needle = target.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = offsets.length - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int cmp = Utf8Compare.compareAt(buf, offsets[mid], needle);
            if (cmp == 0)
                return keys.get(mid);
            if (cmp < 0)
                lo = mid + 1;
            else
                hi = mid - 1;
        }
        return null;
    }
}
