package com.anhtuan.dict.core.lexicon;

import com.anhtuan.dict.core.TestEntries;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.pack.PackWriter;
import com.anhtuan.dict.core.service.DictionaryGlossEngine;
import com.anhtuan.dict.core.service.LookupService;
import com.anhtuan.dict.core.service.RuleBasedTranslationEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.anhtuan.dict.core.TestEntries.entry;
import static com.anhtuan.dict.core.TestEntries.sense;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Bảng xác suất dịch từ: ghi, đọc, và ảnh hưởng thật lên câu dịch.
class LexiconRoundTripTest
{

    @TempDir
    Path tmp;

    private static final List<String> VI_VOCAB = List.of("chính", "phủ", "sự", "cai", "trị", "kế", "hoạch", "sơ", "đồ",
            "quyết", "định");

    private static Path writeLexicon(Path dir)
    {
        List<LexiconWriter.EnglishWord> words = List.of(
                new LexiconWriter.EnglishWord("government", List.of("chính", "phủ", "sự", "cai"),
                        List.of(0.41, 0.39, 0.02, 0.01)),
                new LexiconWriter.EnglishWord("plan", List.of("kế", "hoạch", "sơ", "đồ"),
                        List.of(0.41, 0.40, 0.01, 0.01)));
        Path file = dir.resolve(LexiconFormat.FILE_NAME);
        LexiconWriter.write(file, words, VI_VOCAB);
        return file;
    }

    @Test
    @DisplayName("writes then reads back the exact probabilities")
    void roundTrip()
    {
        Path file = writeLexicon(tmp);
        try (LexicalPrior prior = LexicalPrior.open(file))
        {
            assertTrue(prior.isAvailable());
            assertEquals(2, prior.wordCount());
            // Sai số của lưu 16 bit phải nhỏ hơn 1/65535.
            assertEquals(0.41, prior.probability("government", "chính"), 0.0001);
            assertEquals(0.39, prior.probability("government", "phủ"), 0.0001);
            assertEquals(0.0, prior.probability("government", "hoạch"), 0.0001);
            assertEquals(0.0, prior.probability("khongcotutunay", "chính"), 0.0001);
        }
    }

    @Test
    @DisplayName("a two-syllable gloss is not beaten by a one-syllable gloss")
    void twoSyllableGlossWins()
    {
        Path file = writeLexicon(tmp);
        try (LexicalPrior prior = LexicalPrior.open(file))
        {
            double chinhPhu = prior.scoreGloss("government", List.of("chính", "phủ"));
            double suCaiTri = prior.scoreGloss("government", List.of("sự", "cai", "trị"));
            double phu = prior.scoreGloss("government", List.of("phủ"));
            assertTrue(chinhPhu > suCaiTri, "chính phủ (" + chinhPhu + ") must beat sự cai trị (" + suCaiTri + ")");
            assertTrue(chinhPhu > phu, "divide by the square root, not directly by the syllable count");
        }
    }

    @Test
    @DisplayName("a missing file gives an empty prior instead of throwing")
    void missingFileDegradesGracefully()
    {
        LexicalPrior prior = LexicalPrior.openIfPresent(tmp.resolve("khong-ton-tai.bin"));
        assertFalse(prior.isAvailable());
        assertEquals(0.0, prior.probability("government", "chính"), 0.0);
        prior.close();
    }

    @Test
    @DisplayName("the prior changes the sense chosen in a translation")
    void priorChangesTheChosenSense()
    {
        // Từ điển xếp "sự cai trị" trước "chính phủ" và "sơ đồ" trước "kế hoạch", đúng như nguồn thật;
        // luật ngữ pháp không phân biệt được vì cả hai đều là danh từ.
        List<Entry> dict = List.of(
                entry("government", null, List.of(sense("danh từ", "sự cai trị", "chính phủ, nội các")), List.of()),
                entry("plan", null, List.of(sense("danh từ", "sơ đồ, đồ án", "kế hoạch")), List.of()),
                entry("the", null, List.of(sense("mạo từ", "cái, con")), List.of()));

        Path packFile = tmp.resolve("dict.pack");
        PackWriter.write(packFile, dict);
        Path lexFile = writeLexicon(tmp);

        try (PackReader pack = PackReader.open(packFile))
        {
            LookupService lookup = new LookupService(pack);
            DictionaryGlossEngine gloss = new DictionaryGlossEngine(lookup, pack.multiWordStarters());

            var without = new RuleBasedTranslationEngine(gloss, lookup);
            assertEquals("Sự cai trị", translate(without, "The government"));

            try (LexicalPrior prior = LexicalPrior.open(lexFile))
            {
                var with = new RuleBasedTranslationEngine(gloss, lookup, prior);
                assertEquals("Chính phủ", translate(with, "The government"));
                assertEquals("Kế hoạch", translate(with, "The plan"));
            }
        }
    }

    private static String translate(RuleBasedTranslationEngine engine, String sentence)
    {
        var segments = engine.translate(sentence);
        assertEquals(SegmentKind.TRANSLATED, segments.getFirst().kind());
        return segments.getFirst().displayGloss();
    }

    @Test
    @DisplayName("TestEntries can still build a pack for other tests")
    void fixtureStillBuilds()
    {
        Path packFile = tmp.resolve("mini.pack");
        PackWriter.write(packFile, TestEntries.mini());
        try (PackReader pack = PackReader.open(packFile))
        {
            assertEquals(TestEntries.mini().size(), pack.entryCount());
        }
    }
}
