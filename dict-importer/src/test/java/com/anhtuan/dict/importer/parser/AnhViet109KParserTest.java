package com.anhtuan.dict.importer.parser;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Sense;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

// Test parser trên dữ liệu tự tạo, không cần file 15 MB nên chạy được ở máy khác và trên CI.
// Đối chiếu với file thật nằm ở RealDictionaryFileTest.
class AnhViet109KParserTest
{

    // Trích đoạn thật từ anhviet109K.txt, entry '@about' (ca khó nhất trong file).
    private static final String ABOUT_ENTRY = """
            @about /ə'baut/
            *  phó từ
            - xung quanh, quanh quẩn, đây đó, rải rác
            =he is somewhere about+ anh ta ở quanh quẩn đâu đó
            - đằng sau
            =about turn!+ đằng sau quay
            !about and about
            - (từ Mỹ,nghĩa Mỹ) rất giống nhau
            !to be about
            - bận (làm gì)
            - đã dậy được (sau khi ốm)
            *  giới từ
            - về
            =to know much about Vietnam+ biết nhiều về Việt Nam
            !to be about to
            - sắp, sắp sửa
            =the train is about to start+ xe lửa sắp khởi hành
            """;

    @Test
    @DisplayName("Splits headword and IPA")
    void splitsHeadwordAndIpa()
    {
        var p = AnhViet109KParser.splitHeadword("about /ə'baut/");
        assertEquals("about", p.headword());
        assertEquals("ə'baut", p.ipa());
    }

    @Test
    @DisplayName("Headword without IPA gives ipa = null")
    void headwordWithoutIpa()
    {
        var p = AnhViet109KParser.splitHeadword("about and about");
        assertEquals("about and about", p.headword());
        assertNull(p.ipa());
    }

    @Test
    @DisplayName("Underscore of the source format becomes a space")
    void underscoreBecomesSpace()
    {
        var p = AnhViet109KParser.splitHeadword("a_la_carte /'ɑ:lɑ:'kɑ:t/");
        assertEquals("a la carte", p.headword());
    }

    @Test
    @DisplayName("Headword that itself contains '/' is still split correctly")
    void headwordContainingSlash()
    {
        var p = AnhViet109KParser.splitHeadword("and/or");
        assertEquals("and/or", p.headword());
        assertNull(p.ipa());
    }

    @Test
    @DisplayName("Headword line with variant: '(acid-resisting)' must NOT be swallowed into the headword")
    void variantIsSeparatedFromHeadword()
    {
        var p = AnhViet109KParser.splitHeadword("acid-proof /'æsid'pru:f/ (acid-resisting) /'æsidri'zistiɳ/");
        assertEquals("acid-proof", p.headword(), "must cut at the FIRST '/', not the last");
        assertEquals("'æsid'pru:f", p.ipa());
        assertEquals("acid-resisting", p.variant());
    }

    @Test
    @DisplayName("A '+' line is a cross-reference, not garbage")
    void crossReferenceLineIsCaptured(@TempDir Path dir) throws IOException
    {
        Entry e = parseSingle(dir, """
                @A shares
                - (Econ) Cổ phiếu A.
                + Xem FINANCIAL CAPITAL.
                """);
        assertEquals(1, e.crossRefs().size(), "'+' line treated as garbage instead of a cross-reference");
        assertEquals("Xem FINANCIAL CAPITAL.", e.crossRefs().get(0));
        assertEquals(1, e.senses().get(0).glosses().size());
    }

    @Test
    @DisplayName("Example without '+' is kept with vi = null")
    void exampleWithoutPlusIsKept()
    {
        var ex = AnhViet109KParser.parseExample("just an example", 1);
        assertNotNull(ex);
        assertEquals("just an example", ex.en());
        assertNull(ex.vi());
        assertFalse(ex.hasTranslation());
    }

    @Test
    @DisplayName("TRAP: a '-' line after '!' belongs to the idiom, not the sense")
    void idiomGlossesDoNotLeakIntoSense(@TempDir Path dir) throws IOException
    {
        Entry about = parseSingle(dir, ABOUT_ENTRY);

        // 2 sense: adverb and preposition
        assertEquals(2, about.senses().size(), "must have exactly 2 senses");
        assertEquals("phó từ", about.senses().get(0).pos());
        assertEquals("giới từ", about.senses().get(1).pos());

        // Sense adverb chỉ có 2 nghĩa của riêng nó; nếu parser sai, 3 nghĩa của idiom bị trộn vào thành 5.
        Sense adverb = about.senses().get(0);
        assertEquals(2, adverb.glosses().size(), "idiom glosses leaked into the sense");
        assertEquals("xung quanh, quanh quẩn, đây đó, rải rác", adverb.glosses().get(0));

        // Sense preposition chỉ có 1 nghĩa; nghĩa của '!to be about to' không được lọt vào
        Sense preposition = about.senses().get(1);
        assertEquals(1, preposition.glosses().size());
        assertEquals("về", preposition.glosses().get(0));
    }

    @Test
    @DisplayName("All idioms are recognized, including one between two '*' blocks")
    void collectsAllIdiomsAcrossPosBlocks(@TempDir Path dir) throws IOException
    {
        Entry about = parseSingle(dir, ABOUT_ENTRY);

        assertEquals(3, about.idioms().size());
        assertEquals("about and about", about.idioms().get(0).phrase());
        assertEquals("to be about", about.idioms().get(1).phrase());
        assertEquals("to be about to", about.idioms().get(2).phrase());

        // '!to be about' có 2 nghĩa riêng
        assertEquals(2, about.idioms().get(1).glosses().size());
        // '!to be about to' có ví dụ riêng: idiom cũng mang example được
        assertEquals(1, about.idioms().get(2).examples().size());
        assertEquals("the train is about to start", about.idioms().get(2).examples().get(0).en());
    }

    @Test
    @DisplayName("Example attaches to the nearest gloss above")
    void exampleAttachesToNearestGloss(@TempDir Path dir) throws IOException
    {
        Entry about = parseSingle(dir, ABOUT_ENTRY);
        Sense adverb = about.senses().get(0);

        assertEquals(2, adverb.examples().size());
        assertEquals(0, adverb.examples().get(0).glossIndex(), "example 1 illustrates gloss 0");
        assertEquals(1, adverb.examples().get(1).glossIndex(), "example 2 illustrates gloss 1");
    }

    @Test
    @DisplayName("Entry without any '*' line still creates a sense with pos = null")
    void entryWithoutPosLine(@TempDir Path dir) throws IOException
    {
        Entry e = parseSingle(dir, "@foo /fu:/\n- nghia thu nhat\n- nghia thu hai\n");
        assertEquals(1, e.senses().size());
        assertNull(e.senses().get(0).pos());
        assertEquals(2, e.senses().get(0).glosses().size());
    }

    @Test
    @DisplayName("Malformed lines are skipped, NOT thrown")
    void malformedLinesAreSkippedNotThrown(@TempDir Path dir) throws IOException
    {
        Path f = write(dir, """
                @good /gud/
                *  tính từ
                - tốt
                dòng rác không đúng định dạng
                ??? một dòng rác nữa
                @bad /bæd/
                - xấu
                """);
        AnhViet109KParser parser = new AnhViet109KParser();
        List<Entry> entries;
        try (Stream<Entry> s = parser.parse(f, 0))
        {
            entries = s.toList();
        }
        assertEquals(2, entries.size(), "both entries must still be parsed");
        assertEquals(2, parser.malformedLineCount(), "must count 2 malformed lines");
        assertFalse(parser.malformedSamples().isEmpty());
    }

    @Test
    @DisplayName("UTF-8 BOM at file start is stripped, not stuck to the first headword")
    void stripsUtf8Bom(@TempDir Path dir) throws IOException
    {
        Path f = dir.resolve("bom.txt");
        Files.writeString(f, "﻿@a /ei/\n- loại a\n", StandardCharsets.UTF_8);
        try (Stream<Entry> s = new AnhViet109KParser().parse(f, 0))
        {
            Entry e = s.findFirst().orElseThrow();
            assertEquals("a", e.headword(), "BOM was not stripped from the headword");
            assertEquals("a", e.headwordNorm());
        }
    }

    @Test
    @DisplayName("sourceId is propagated to every entry")
    void sourceIdIsPropagated(@TempDir Path dir) throws IOException
    {
        Path f = write(dir, "@x /x/\n- test\n");
        try (Stream<Entry> s = new AnhViet109KParser().parse(f, 7))
        {
            assertEquals(7, s.findFirst().orElseThrow().sourceId());
        }
    }

    @Test
    @DisplayName("canParse recognizes the format")
    void canParseDetectsFormat(@TempDir Path dir) throws IOException
    {
        AnhViet109KParser parser = new AnhViet109KParser();
        assertTrue(parser.canParse(write(dir, "@about /x/\n- về\n")));
        assertFalse(parser.canParse(write(dir, "day khong phai tu dien\nchi la van ban thuong\n")));
    }

    private static Entry parseSingle(Path dir, String content) throws IOException
    {
        Path f = write(dir, content);
        try (Stream<Entry> s = new AnhViet109KParser().parse(f, 0))
        {
            return s.findFirst().orElseThrow(() -> new AssertionError("no entry parsed"));
        }
    }

    private static Path write(Path dir, String content) throws IOException
    {
        Path f = Files.createTempFile(dir, "dict", ".txt");
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }
}
