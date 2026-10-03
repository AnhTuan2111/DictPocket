package com.anhtuan.dict.importer.parser;

import com.anhtuan.dict.core.model.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Đọc từ điển dạng bảng TAB, định dạng để người dùng tự thêm nguồn.
class TsvDictParserTest
{

    @TempDir
    Path tmp;

    private Path write(String name, String content) throws IOException
    {
        Path file = tmp.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("reads all three columns, the part-of-speech column may be absent")
    void readsColumns() throws IOException
    {
        Path file = write("a.tsv", """
                # dong chu thich bi bo qua
                deadline\thạn chót, thời hạn cuối\tdanh từ
                merge\tgộp nhánh
                """);
        TsvDictParser parser = new TsvDictParser();
        try (Stream<Entry> stream = parser.parse(file, 7))
        {
            List<Entry> entries = stream.toList();
            assertEquals(2, entries.size());

            Entry first = entries.getFirst();
            assertEquals("deadline", first.headword());
            assertEquals(7, first.sourceId(), "sourceId must follow the assigned source");
            assertEquals("danh từ", first.senses().getFirst().pos());
            assertEquals("hạn chót, thời hạn cuối", first.senses().getFirst().glosses().getFirst());
            assertNull(entries.get(1).senses().getFirst().pos());
        }
    }

    @Test
    @DisplayName("malformed lines are skipped without aborting the whole import")
    void skipsMalformedLines() throws IOException
    {
        Path file = write("b.tsv", "thieu-cot\n\ndeadline\thạn chót\n\t\nok\tđược\n");
        try (Stream<Entry> stream = new TsvDictParser().parse(file, 0))
        {
            assertEquals(2, stream.count());
        }
    }

    @Test
    @DisplayName("does not claim files in the 109K format")
    void doesNotClaimOtherFormats() throws IOException
    {
        Path tsv = write("c.tsv", "deadline\thạn chót\n");
        Path anhviet = write("d.txt", "@about /ə'baut/\n* phó từ\n- xung quanh\n");

        assertTrue(new TsvDictParser().canParse(tsv));
        assertFalse(new TsvDictParser().canParse(anhviet), "misdetecting the parser would corrupt the data silently");
        assertTrue(new AnhViet109KParser().canParse(anhviet));
    }
}
