package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.TestEntries;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.pack.PackWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlossEngineTest
{

    @TempDir
    Path tmp;

    private PackReader pack;
    private DictionaryGlossEngine engine;

    @BeforeEach
    void setUp()
    {
        Path file = tmp.resolve("dict.pack");
        PackWriter.write(file, TestEntries.mini());
        pack = PackReader.open(file);
        engine = new DictionaryGlossEngine(new LookupService(pack), pack.multiWordStarters());
    }

    @AfterEach
    void tearDown()
    {
        pack.close();
    }

    private Segment segmentOf(List<Segment> segments, String source)
    {
        return segments.stream().filter(s -> s.sourceText().equalsIgnoreCase(source)).findFirst()
                .orElseThrow(() -> new AssertionError("no segment \"" + source + "\""));
    }

    @Test
    @DisplayName("\"He gave up his job\" recognises the phrase give up via inflection + lemma")
    void recognisesPhrasalVerbInPastTense()
    {
        List<Segment> segments = engine.translate("He gave up his job.");
        Segment phrase = segmentOf(segments, "gave up");
        assertEquals(SegmentKind.PHRASE, phrase.kind());
        assertEquals("bỏ, từ bỏ", phrase.displayGloss());
        assertTrue(phrase.candidates().size() > 1, "alternative glosses must exist so the user can switch");
    }

    @Test
    @DisplayName("longest-match: \"look after\" is not split into look + after")
    void longestMatchWins()
    {
        List<Segment> segments = engine.translate("She looks after them");
        Segment phrase = segmentOf(segments, "looks after");
        assertEquals(SegmentKind.PHRASE, phrase.kind());
        assertEquals("chăm sóc, trông nom", phrase.displayGloss());
    }

    @Test
    @DisplayName("a cross-reference-only entry still yields a gloss: went -> go")
    void crossReferenceOnlyEntryFallsThroughToLemma()
    {
        // "@went" tồn tại nhưng không có dòng nghĩa nào; dừng ở đây thì UI in ra ô trống.
        Segment went = segmentOf(engine.translate("She went home"), "went");
        assertEquals(SegmentKind.WORD, went.kind());
        assertNotNull(went.displayGloss());
        assertEquals("go", went.candidates().getFirst().headword());
    }

    @Test
    @DisplayName("\"about to\" is absent from the source -> looked up word by word without error")
    void missingPhraseDegradesGracefully()
    {
        List<Segment> segments = engine.translate("He is about to leave");
        assertTrue(segments.stream().noneMatch(s -> s.kind() == SegmentKind.PHRASE));
        assertEquals(SegmentKind.UNKNOWN, segmentOf(segments, "about").kind());
    }

    @Test
    @DisplayName("Invariant: segments tile the source sentence at exact offsets")
    void segmentsTileTheSourceExactly()
    {
        String sentence = "He gave up his job, then went home.";
        StringBuilder sb = new StringBuilder();
        int expectedStart = 0;
        for (Segment s : engine.translate(sentence))
        {
            assertEquals(expectedStart, s.startOffset());
            assertEquals(s.sourceText(), sentence.substring(s.startOffset(), s.endOffset()));
            expectedStart = s.endOffset();
            sb.append(s.sourceText());
        }
        assertEquals(sentence, sb.toString());
    }

    @Test
    @DisplayName("a word missing from the dictionary is kept as is, no exception")
    void unknownWordsAreKept()
    {
        Segment seg = segmentOf(engine.translate("zzzblah"), "zzzblah");
        assertEquals(SegmentKind.UNKNOWN, seg.kind());
        assertTrue(seg.candidates().isEmpty());
        assertEquals("zzzblah", DictionaryGlossEngine.flatten(engine.translate("zzzblah")));
    }
}
