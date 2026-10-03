package com.anhtuan.dict.core.pack;

import com.anhtuan.dict.core.TestEntries;
import com.anhtuan.dict.core.model.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ghi dict.pack rồi đọc lại. Lỗi nguy hiểm nhất là lệch một varint hoặc quy tắc sắp xếp giữa lúc ghi và đọc:
// không crash, chỉ thỉnh thoảng không ra từ. Vì vậy so sánh toàn bộ entry chứ không chỉ đếm số lượng.
class PackRoundTripTest
{

    @TempDir
    Path tmp;

    private PackReader writeAndOpen(List<Entry> entries)
    {
        Path pack = tmp.resolve("dict.pack");
        PackWriter.write(pack, entries);
        return PackReader.open(pack);
    }

    @Test
    @DisplayName("reads back every entry that was written")
    void roundTripPreservesEveryField()
    {
        List<Entry> entries = TestEntries.mini();
        try (PackReader reader = writeAndOpen(entries))
        {
            assertEquals(entries.size(), reader.entryCount());

            Entry give = reader.lookup("give").orElseThrow();
            Entry original = entries.stream().filter(e -> e.headword().equals("give")).findFirst().orElseThrow();
            assertEquals(original, give, "entry read back must equal the original");
        }
    }

    @Test
    @DisplayName("alias key: looking up \"give up\" returns entry \"give\"")
    void aliasKeysPointToParentEntry()
    {
        try (PackReader reader = writeAndOpen(TestEntries.mini()))
        {
            // "give up" không phải headword, chỉ là dòng "!to give up" trong "@give".
            assertTrue(reader.lookup("give up").isPresent(), "missing alias key without the 'to' prefix");
            assertEquals("give", reader.lookup("give up").orElseThrow().headwordNorm());
            assertEquals("give", reader.lookup("to give up").orElseThrow().headwordNorm());
            assertEquals("look", reader.lookup("look after").orElseThrow().headwordNorm());

            assertTrue(reader.keyCount() > reader.entryCount(), "key count must exceed entry count because of aliases");
        }
    }

    @Test
    @DisplayName("homographs: \"bank\" returns both entries")
    void homographsAreAllReturned()
    {
        try (PackReader reader = writeAndOpen(TestEntries.mini()))
        {
            List<Entry> banks = reader.lookupAll("bank");
            assertEquals(2, banks.size());
            assertTrue(banks.stream().anyMatch(e -> e.senses().getFirst().glosses().getFirst().contains("bờ sông")));
            assertTrue(banks.stream().anyMatch(e -> e.senses().getFirst().glosses().getFirst().contains("ngân hàng")));
        }
    }

    @Test
    @DisplayName("duplicate keys: lookup() always returns the first entry regardless of key count")
    void lookupIsStableAcrossDuplicateKeys()
    {
        // Lỗi thật đã gặp: binary search rơi vào bất kỳ mục nào trong nhóm khóa trùng, tùy tổng số khóa trong file,
        // nên thêm một nguồn từ điển làm đổi kết quả tra của một từ không liên quan.
        List<Entry> entries = new java.util.ArrayList<>(TestEntries.mini());
        try (PackReader reader = writeAndOpen(entries))
        {
            assertEquals("bờ sông, bờ đê",
                    reader.lookup("bank").orElseThrow().senses().getFirst().glosses().getFirst());
        }
        // Thêm 50 mục từ không liên quan: số khóa đổi hẳn, kết quả tra "bank" phải y nguyên.
        for (int i = 0; i < 50; i++)
        {
            entries.add(
                    TestEntries.entry("zzz" + i, null, List.of(TestEntries.sense("danh từ", "rác " + i)), List.of()));
        }
        try (PackReader reader = writeAndOpen(entries))
        {
            assertEquals("bờ sông, bờ đê",
                    reader.lookup("bank").orElseThrow().senses().getFirst().glosses().getFirst());
        }
    }

    @Test
    @DisplayName("contains() works without decompressing a block")
    void containsWorksOnNormalizedKeys()
    {
        try (PackReader reader = writeAndOpen(TestEntries.mini()))
        {
            assertTrue(reader.contains("a la carte"));
            assertFalse(reader.contains("about to")); // giới hạn của nguồn, không phải bug
        }
    }

    @Test
    @DisplayName("prefixScan returns keys in UTF-8 byte order")
    void prefixScanIsOrdered()
    {
        try (PackReader reader = writeAndOpen(TestEntries.mini()))
        {
            List<String> keys = reader.prefixScan("g", 10);
            // Thứ tự byte UTF-8: dấu cách (0x20) nhỏ hơn mọi chữ cái nên "give in" đứng trước "gives";
            // đây là chỗ String::compareTo sẽ sai.
            assertEquals(List.of("give", "give in", "give up", "go"), keys);
        }
    }

    @Test
    @DisplayName("multi-word starters cover the aliases")
    void multiWordStartersCoverAliases()
    {
        try (PackReader reader = writeAndOpen(TestEntries.mini()))
        {
            var starters = reader.multiWordStarters();
            assertTrue(starters.contains("give"));
            assertTrue(starters.contains("look"));
            assertTrue(starters.contains("to")); // từ "to give up"
            assertFalse(starters.contains("run")); // "run" không mở đầu cụm nào
        }
    }

    @Test
    @DisplayName("close() releases the file immediately so it can be overwritten on Windows")
    void closeUnmapsImmediately() throws Exception
    {
        Path pack = tmp.resolve("dict.pack");
        PackWriter.write(pack, TestEntries.mini());
        PackReader reader = PackReader.open(pack);
        reader.close();

        // Nếu còn MappedByteBuffer chờ GC dọn thì dòng này ném AccessDeniedException;
        // đó là lý do PackReader dùng Arena thay vì FileChannel.map.
        PackWriter.write(pack, TestEntries.mini());
        assertTrue(Files.size(pack) > 0);
    }
}
