package com.anhtuan.dict.core.index;

import com.anhtuan.dict.core.TestEntries;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.pack.PackWriter;
import com.anhtuan.dict.core.service.ReverseSearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Inverted index và tìm Việt->Anh.
class IndexRoundTripTest
{

    @TempDir
    Path tmp;

    private record Fixture(PackReader pack, InvertedIndex vi, InvertedIndex viNoDiac, InvertedIndex tri,
            ReverseSearchService search) implements AutoCloseable
    {
        @Override
        public void close()
        {
            tri.close();
            viNoDiac.close();
            vi.close();
            pack.close();
        }
    }

    private Fixture build()
    {
        List<Entry> entries = TestEntries.mini();
        PackWriter.write(tmp.resolve("dict.pack"), entries);
        IndexWriter.build(tmp, entries);

        PackReader pack = PackReader.open(tmp.resolve("dict.pack"));
        InvertedIndex vi = InvertedIndex.open(tmp.resolve(IndexFormat.VI_INDEX));
        InvertedIndex viNo = InvertedIndex.open(tmp.resolve(IndexFormat.VI_NODIAC_INDEX));
        InvertedIndex tri = InvertedIndex.open(tmp.resolve(IndexFormat.TRIGRAM_INDEX));
        return new Fixture(pack, vi, viNo, tri, new ReverseSearchService(pack, vi, viNo, tri));
    }

    @Test
    @DisplayName("postings read back with correct docId and frequency")
    void postingsRoundTrip()
    {
        try (Fixture f = build())
        {
            List<int[]> postings = f.vi().postings("chăm");
            assertFalse(postings.isEmpty(), "term 'chăm' must be in the index");
            for (int[] p : postings)
            {
                assertTrue(p[0] >= 0 && p[0] < f.pack().entryCount(), "docId outside the pack");
                assertTrue(p[1] >= 1, "frequency must be >= 1");
            }
            assertEquals(postings.size(), f.vi().docFreq("chăm"));
        }
    }

    @Test
    @DisplayName("index docId points at the right pack entry")
    void docIdsMatchPackOrdinals()
    {
        try (Fixture f = build())
        {
            int docId = f.vi().postings("ngân").getFirst()[0];
            Entry entry = f.pack().entryAt(docId);
            assertEquals("bank", entry.headwordNorm());
        }
    }

    @Test
    @DisplayName("Vietnamese search with diacritics finds the right English word")
    void searchWithDiacritics()
    {
        try (Fixture f = build())
        {
            List<ReverseSearchService.Hit> hits = f.search().searchVietnamese("chăm sóc", 5);
            assertFalse(hits.isEmpty());
            assertEquals("look", hits.getFirst().entry().headwordNorm());
            // Nghĩa khớp nằm ở dòng thành ngữ nên hiện cả cụm, không chỉ chữ "look".
            assertEquals("to look after", hits.getFirst().display());
        }
    }

    @Test
    @DisplayName("typing without diacritics gives the same results")
    void searchWithoutDiacritics()
    {
        try (Fixture f = build())
        {
            List<String> withMarks = f.search().searchVietnamese("chăm sóc", 5).stream()
                    .map(h -> h.entry().headwordNorm()).toList();
            List<String> without = f.search().searchVietnamese("cham soc", 5).stream()
                    .map(h -> h.entry().headwordNorm()).toList();
            assertEquals(withMarks, without, "both search paths must give the same results");
        }
    }

    @Test
    @DisplayName("misspelled input still finds the right word")
    void fuzzyEnglishFindsTypos()
    {
        try (Fixture f = build())
        {
            List<ReverseSearchService.Hit> hits = f.search().fuzzyEnglish("bannk", 3);
            assertFalse(hits.isEmpty());
            assertEquals("bank", hits.getFirst().entry().headwordNorm());
        }
    }

    @Test
    @DisplayName("trigrams are padded at both ends to mark word start/end")
    void trigramsArePadded()
    {
        assertEquals(List.of("$$g", "$go", "go$", "o$$"), TrigramIndex.trigrams("go"));
    }

    @Test
    @DisplayName("BM25: rare terms score higher than common terms")
    void rareTermsScoreHigher()
    {
        BM25Scorer scorer = new BM25Scorer(1000, 10);
        assertTrue(scorer.idf(1) > scorer.idf(500));
        assertTrue(scorer.score(1, 3, 10) > scorer.score(1, 1, 10), "higher frequency -> higher score");
    }
}
