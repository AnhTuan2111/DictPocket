package com.anhtuan.dict.core.nlp;

import com.anhtuan.dict.core.index.IndexWriter;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Sense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.anhtuan.dict.core.TestEntries.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViCompoundsTest
{

    @TempDir
    Path tmp;

    private static Entry withGlosses(String headword, String... glosses)
    {
        return entry(headword, null, List.of(new Sense("động từ", List.of(glosses), List.of())), List.of());
    }

    // Bốn mục: "chăm sóc" lặp 3 lần, "linh tinh quá" chỉ 1 lần.
    private static List<Entry> dictionary()
    {
        List<Entry> out = new ArrayList<>();
        out.add(withGlosses("care", "trông nom, chăm sóc"));
        out.add(withGlosses("tend", "chăm sóc, săn sóc"));
        out.add(withGlosses("nurse", "chăm sóc người ốm", "chăm sóc"));
        out.add(withGlosses("blah", "linh tinh quá"));
        return out;
    }

    @Test
    @DisplayName("only alternatives repeated often enough count as compounds")
    void onlyRepeatedAlternativesBecomeWords()
    {
        Set<String> words = IndexWriter.mineCompounds(dictionary(), 3);
        assertTrue(words.contains("chăm sóc"), "appears 3 times -> compound");
        assertFalse(words.contains("linh tinh quá"), "appears once -> just a random phrase");
        assertFalse(words.contains("trông nom"), "appears once");
    }

    @Test
    @DisplayName("merges syllables into words while keeping the loose syllables")
    void expandKeepsSyllablesAndAddsCompounds()
    {
        ViCompounds words = ViCompounds.of(Set.of("chăm sóc", "sự chăm sóc"));
        List<String> out = words.expand(List.of("sự", "chăm", "sóc"));
        assertTrue(out.containsAll(List.of("sự", "chăm", "sóc")), "loose syllables must remain");
        assertTrue(out.contains("chăm_sóc"));
        // Khớp TẤT CẢ chứ không chỉ cụm dài nhất: gõ "chăm sóc" vẫn phải tìm ra "sự chăm sóc".
        assertTrue(out.contains("sự_chăm_sóc"));
    }

    @Test
    @DisplayName("a Vietnamese typo suggests the correct word")
    void suggestsCorrectionForTypos()
    {
        ViCompounds words = ViCompounds.of(Set.of("chăm sóc", "ngân hàng", "nghiên cứu"));
        // Trước khi có hàm này, gõ "cham sok" ra slow / sculp / shock.
        assertEquals(List.of("chăm sóc"), words.suggest("cham sok", 3));
        assertEquals(List.of("ngân hàng"), words.suggest("ngan hag", 3));
    }

    @Test
    @DisplayName("a correct query yields no suggestion")
    void noSuggestionWhenQueryIsCorrect()
    {
        ViCompounds words = ViCompounds.of(Set.of("chăm sóc"));
        assertTrue(words.suggest("chăm sóc", 3).isEmpty());
        assertTrue(words.suggest("cham soc", 3).isEmpty(), "accent-free input still counts as correct");
        assertTrue(words.suggest("sóc", 3).isEmpty(), "a single syllable is not guessed");
    }

    @Test
    @DisplayName("written list reads back identically")
    void roundTrip()
    {
        Path file = tmp.resolve(ViCompounds.FILE_NAME);
        ViCompounds.write(file, Set.of("chăm sóc", "ngân hàng"));
        ViCompounds loaded = ViCompounds.loadIfPresent(file);
        assertEquals(2, loaded.size());
        assertTrue(loaded.contains("chăm sóc"));

        ViCompounds missing = ViCompounds.loadIfPresent(tmp.resolve("khong-co.txt"));
        assertFalse(missing.isAvailable());
        assertEquals(List.of("a", "b"), missing.expand(List.of("a", "b")), "an empty list changes nothing");
    }
}
