package com.anhtuan.dict.desktop.userdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryStoreTest
{
    @TempDir
    Path tmp;

    private static Clock at(long epochSecond)
    {
        return Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("entries are listed newest first")
    void newestFirst()
    {
        HistoryStore store = HistoryStore.inMemory();
        store.add("word", "about");
        store.add("sentence", "He gave up his job");
        store.add("reverse", "chăm sóc");
        List<HistoryStore.Entry> entries = store.entries();
        assertEquals(List.of("chăm sóc", "He gave up his job", "about"), entries.stream().map(HistoryStore.Entry::query).toList());
    }

    @Test
    @DisplayName("a repeated lookup is listed once, at its latest position")
    void repeatsAreListedOnce()
    {
        HistoryStore store = HistoryStore.inMemory();
        store.add("word", "give up");
        store.add("word", "about");
        store.add("word", "give up");
        assertEquals(List.of("give up", "about"), store.entries().stream().map(HistoryStore.Entry::query).toList());
        // Cùng nội dung nhưng khác chế độ là hai mục khác nhau
        store.add("sentence", "give up");
        assertEquals(3, store.entries().size());
    }

    @Test
    @DisplayName("empty input is ignored, tabs and line breaks become spaces")
    void sanitizesInput()
    {
        HistoryStore store = HistoryStore.inMemory();
        store.add("word", "   ");
        store.add("word", null);
        store.add("sentence", "first line\nsecond\tline\r\nthird");
        assertEquals(1, store.entries().size());
        assertEquals("first line second line third", store.entries().getFirst().query());
    }

    @Test
    @DisplayName("history survives closing and reopening the file")
    void persistsAcrossRestarts()
    {
        Path file = tmp.resolve("data").resolve("history.tsv");
        HistoryStore first = HistoryStore.open(file, at(1_000));
        first.add("word", "give up");
        first.add("reverse", "chăm sóc");

        HistoryStore second = HistoryStore.open(file);
        assertEquals(List.of("chăm sóc", "give up"), second.entries().stream().map(HistoryStore.Entry::query).toList());
        assertEquals(1_000, second.entries().getFirst().epochSecond());
        assertEquals("reverse", second.entries().getFirst().mode());
    }

    @Test
    @DisplayName("removing one entry and clearing everything are saved to the file")
    void removeAndClearArePersisted()
    {
        Path file = tmp.resolve("history.tsv");
        HistoryStore store = HistoryStore.open(file);
        store.add("word", "a1");
        store.add("word", "b2");
        store.add("word", "c3");
        store.remove(store.entries().get(1));
        assertEquals(List.of("c3", "a1"), HistoryStore.open(file).entries().stream().map(HistoryStore.Entry::query).toList());

        store.clear();
        assertTrue(HistoryStore.open(file).entries().isEmpty());
    }

    @Test
    @DisplayName("malformed lines in the file are skipped")
    void toleratesBrokenLines() throws IOException
    {
        Path file = tmp.resolve("history.tsv");
        Files.writeString(file, "garbage line\nnot-a-number\tword\tx\n10\tword\tgood one\n20\t\tno mode\n\n30\tsentence\t\n",
                StandardCharsets.UTF_8);
        HistoryStore store = HistoryStore.open(file);
        assertEquals(List.of("good one"), store.entries().stream().map(HistoryStore.Entry::query).toList());
    }

    @Test
    @DisplayName("an oversized file is compacted to the newest entries")
    void compactsWhenTooLarge() throws IOException
    {
        Path file = tmp.resolve("history.tsv");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++)
        {
            sb.append(i).append("\tword\tq").append(i).append('\n');
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);

        HistoryStore store = HistoryStore.open(file);
        assertEquals(1000, store.entries().size());
        assertEquals("q2499", store.entries().getFirst().query());
        assertEquals(1000, Files.readAllLines(file, StandardCharsets.UTF_8).size());
    }

    @Test
    @DisplayName("an unwritable location does not break lookups, history stays in memory")
    void unwritableFileIsHarmless() throws IOException
    {
        // Một thư mục nằm đúng chỗ file lịch sử nên không thể ghi vào được
        Path file = tmp.resolve("history.tsv");
        Files.createDirectory(file);
        HistoryStore store = HistoryStore.open(file);
        assertDoesNotThrow(() -> store.add("word", "give up"));
        assertEquals(1, store.entries().size());
        assertDoesNotThrow(store::clear);
    }
}
