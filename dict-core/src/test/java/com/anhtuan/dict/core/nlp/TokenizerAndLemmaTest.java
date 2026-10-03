package com.anhtuan.dict.core.nlp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenizerAndLemmaTest
{

    @Test
    @DisplayName("Invariant: concatenated tokens reproduce the original sentence")
    void tokensCoverTheWholeInput()
    {
        String text = "He gave up his job, didn't he?  Yes - the acid-proof one.";
        StringBuilder sb = new StringBuilder();
        int expectedStart = 0;
        for (Tokenizer.Token t : Tokenizer.tokenize(text))
        {
            assertEquals(expectedStart, t.start(), "tokens must be contiguous, no gaps");
            assertEquals(t.text(), text.substring(t.start(), t.end()));
            expectedStart = t.end();
            sb.append(t.text());
        }
        assertEquals(text, sb.toString());
        assertEquals(text.length(), expectedStart);
    }

    @Test
    @DisplayName("apostrophes and hyphens stay inside words, punctuation does not")
    void apostropheAndHyphenStayInsideWords()
    {
        List<String> words = Tokenizer.tokenize("didn't acid-proof end.").stream().filter(Tokenizer.Token::isWord)
                .map(Tokenizer.Token::text).toList();
        assertEquals(List.of("didn't", "acid-proof", "end"), words);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"went, go", // bất quy tắc
            "gave, give", "better, good", "mice, mouse", "children, child", "worst, bad"})
    @DisplayName("irregular table yields real lemmas, not truncated stems")
    void irregularFormsResolve(String word, String expected)
    {
        assertEquals(expected, Lemmatizer.lemma(word));
    }

    @ParameterizedTest(name = "{0} must have candidate {1}")
    @CsvSource({"running, run", // hoàn nguyên phụ âm đôi
            "stopped, stop", "studies, study", // -ies -> y
            "studied, study", "making, make", // thêm lại 'e' bị rụng
            "moved, move", "quickly, quick", "boxes, box"})
    @DisplayName("suffix rules generate candidates, the dictionary decides")
    void suffixRulesProduceTheRightCandidate(String word, String expected)
    {
        List<String> candidates = Lemmatizer.candidates(word);
        assertTrue(candidates.contains(expected), word + " -> " + candidates + " is missing " + expected);
    }

    @Test
    @DisplayName("must not behave like a Porter stemmer")
    void neverProducesNonWords()
    {
        // Porter stemmer ra "runn" nên tra từ điển trượt; ứng viên đúng phải đứng đầu danh sách.
        assertEquals("run", Lemmatizer.candidates("running").getFirst());
        assertFalse(Lemmatizer.candidates("running").isEmpty());
    }

    @Test
    @DisplayName("Vietnamese tokens keep diacritics, shared by build and query")
    void vietnameseTokensKeepDiacritics()
    {
        assertEquals(List.of("sự", "chăm", "sóc"), TextNormalizer.splitTokens("sự chăm sóc;"));
        assertEquals(List.of("su", "cham", "soc"),
                TextNormalizer.splitTokens(TextNormalizer.removeDiacritics("sự chăm sóc")));
    }
}
