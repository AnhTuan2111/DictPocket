package com.anhtuan.dict.importer.parser;

import com.anhtuan.dict.core.model.Entry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Nghiệm thu parser trên FILE THẬT, đối chiếu với số liệu đã đo. Nếu một con số lệch thì parser
// hiểu sai văn phạm, loại lỗi im lặng nguy hiểm nhất nên phải chặn bằng test.
// Tự động bỏ qua nếu không tìm thấy file (máy khác, CI) để "mvn test" vẫn xanh.
class RealDictionaryFileTest
{

    // Số liệu chuẩn, đo lại sau khi sửa quy tắc tách headword. Con số ban đầu (108.853 / 11.941)
    // sai vì grep '^@' bỏ sót dòng đầu có BOM, và quy tắc cũ lấy '/' cuối nên nuốt phần biến thể
    // "(acid-resisting)" vào headword, làm phồng số cụm từ lên 18.862.
    private static final int EXPECTED_ENTRIES = 108_854;
    private static final int EXPECTED_MULTIWORD = 11_956;
    private static final int EXPECTED_IDIOMS = 9_791;
    // Phần lớn là 1.202 dòng '/' lạc: dữ liệu nguồn hỏng thật, không sửa được.
    private static final int MAX_MALFORMED = 1_400;

    @Test
    @DisplayName("Parses the real file: exact entry count, no exception")
    void parsesRealFileWithExpectedCounts()
    {
        Path source = locateDictionary().orElse(null);
        assumeTrue(source != null, "anhviet109K.txt not found - skipping this test");

        AnhViet109KParser parser = new AnhViet109KParser();
        int entries = 0, multiWord = 0, idioms = 0, glosses = 0, examples = 0;

        long t0 = System.nanoTime();
        try (Stream<Entry> stream = parser.parse(source, 0))
        {
            for (Entry e : (Iterable<Entry>) stream::iterator)
            {
                entries++;
                if (e.isMultiWord())
                    multiWord++;
                idioms += e.idioms().size();
                for (var s : e.senses())
                {
                    glosses += s.glosses().size();
                    examples += s.examples().size();
                }
                for (var i : e.idioms())
                {
                    glosses += i.glosses().size();
                    examples += i.examples().size();
                }
            }
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("""
                === Parse anhviet109K.txt ===
                entry         : %,d
                  multi-word  : %,d
                gloss         : %,d
                example       : %,d
                idiom         : %,d
                bad lines     : %,d
                time          : %,d ms
                """, entries, multiWord, glosses, examples, idioms, parser.malformedLineCount(), ms);
        parser.malformedSamples().forEach(s -> System.out.println("  ! " + s));

        assertEquals(EXPECTED_ENTRIES, entries, "entry count differs from the measured value");
        assertEquals(EXPECTED_MULTIWORD, multiWord, "multi-word count differs from the measured value");
        assertEquals(EXPECTED_IDIOMS, idioms, "idiom count differs from the measured value");
        assertTrue(parser.malformedLineCount() < MAX_MALFORMED,
                "too many malformed lines (" + parser.malformedLineCount() + ") - parser may misread the grammar");
    }

    @Test
    @DisplayName("Entry '@about' in the real file is parsed correctly")
    void parsesAboutEntryFromRealFile()
    {
        Path source = locateDictionary().orElse(null);
        assumeTrue(source != null, "anhviet109K.txt not found - skipping this test");

        Entry about;
        try (Stream<Entry> stream = new AnhViet109KParser().parse(source, 0))
        {
            about = stream.filter(e -> e.headwordNorm().equals("about")).findFirst().orElseThrow();
        }

        assertEquals("ə'baut", about.ipa());
        assertEquals(3, about.senses().size(), "'about' has exactly 3 parts of speech in the real file");
        assertEquals("phó từ", about.senses().get(0).pos());
        assertEquals("giới từ", about.senses().get(1).pos());
        assertEquals("ngoại động từ", about.senses().get(2).pos());
        assertEquals(5, about.idioms().size(), "'about' has exactly 5 idioms in the real file");
        assertFalse(about.isMultiWord());
    }

    @Test
    @DisplayName("Headword line with a variant is split correctly, not swallowed into the headword")
    void parsesVariantHeadword()
    {
        Path source = locateDictionary().orElse(null);
        assumeTrue(source != null, "anhviet109K.txt not found - skipping this test");

        Entry e;
        try (Stream<Entry> stream = new AnhViet109KParser().parse(source, 0))
        {
            e = stream.filter(x -> x.headwordNorm().equals("acid-proof")).findFirst().orElseThrow();
        }
        assertEquals("acid-proof", e.headword(), "variant swallowed into the headword");
        assertEquals("'æsid'pru:f", e.ipa());
        assertEquals("acid-resisting", e.variant());
        assertFalse(e.isMultiWord(), "'acid-proof' is a single word, not a phrase");
    }

    @Test
    @DisplayName("A '+' line is kept as a cross-reference, not treated as garbage")
    void capturesCrossReferences()
    {
        Path source = locateDictionary().orElse(null);
        assumeTrue(source != null, "anhviet109K.txt not found - skipping this test");

        long withRefs;
        try (Stream<Entry> stream = new AnhViet109KParser().parse(source, 0))
        {
            withRefs = stream.filter(e -> !e.crossRefs().isEmpty()).count();
        }
        assertTrue(withRefs > 1_000, "only " + withRefs + " entries have cross-references, expected more than 1,000");
    }

    // Tìm file từ điển: data/ trước, rồi thư mục gốc repo, đi ngược lên tối đa 4 cấp.
    static Optional<Path> locateDictionary()
    {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++, dir = dir.getParent())
        {
            for (String candidate : new String[]{"data/anhviet109K.txt", "anhviet109K.txt"})
            {
                Path p = dir.resolve(candidate);
                if (Files.isRegularFile(p))
                    return Optional.of(p);
            }
        }
        return Optional.empty();
    }
}
