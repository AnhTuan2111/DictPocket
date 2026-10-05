package com.anhtuan.dict.core.nlp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentenceSplitterTest
{
    @Test
    @DisplayName("a paragraph is split at each sentence end and keeps its punctuation")
    void splitsParagraph()
    {
        assertEquals(List.of("I woke up early.", "I made some coffee!", "Did you sleep well?"),
                SentenceSplitter.split("I woke up early. I made some coffee!  Did you sleep well?"));
    }

    @Test
    @DisplayName("a single sentence, with or without final punctuation, stays one piece")
    void singleSentence()
    {
        assertEquals(List.of("He gave up his job"), SentenceSplitter.split("He gave up his job"));
        assertEquals(List.of("He gave up his job."), SentenceSplitter.split("  He gave up his job.  "));
    }

    @Test
    @DisplayName("empty or null input gives no sentences")
    void emptyInput()
    {
        assertTrue(SentenceSplitter.split("").isEmpty());
        assertTrue(SentenceSplitter.split("   ").isEmpty());
        assertTrue(SentenceSplitter.split(null).isEmpty());
    }

    @Test
    @DisplayName("known abbreviations and initials do not end a sentence")
    void abbreviationsAreNotBoundaries()
    {
        assertEquals(List.of("Dr. Smith met Mr. Jones in the U.S. last year.", "They talked."),
                SentenceSplitter.split("Dr. Smith met Mr. Jones in the U.S. last year. They talked."));
        assertEquals(List.of("J. K. Rowling wrote the books."), SentenceSplitter.split("J. K. Rowling wrote the books."));
    }

    @Test
    @DisplayName("a period followed by a lowercase word or a decimal is not a boundary")
    void lowercaseOrDecimalContinues()
    {
        assertEquals(List.of("Use fruit, e.g. apples and pears."), SentenceSplitter.split("Use fruit, e.g. apples and pears."));
        assertEquals(List.of("The value is 3.5 percent.", "It rose."), SentenceSplitter.split("The value is 3.5 percent. It rose."));
    }

    @Test
    @DisplayName("closing quotes stay with the sentence they close")
    void quotesStayWithTheirSentence()
    {
        assertEquals(List.of("He said, \"Go home.\"", "She left."), SentenceSplitter.split("He said, \"Go home.\" She left."));
    }

    @Test
    @DisplayName("line breaks are sentence boundaries")
    void lineBreaksSplit()
    {
        assertEquals(List.of("First line", "Second line"), SentenceSplitter.split("First line\nSecond line"));
    }

    @Test
    @DisplayName("an overlong sentence is cut at commas, each piece within the limit")
    void splitLongCutsAtCommas()
    {
        String longSentence = "alpha beta gamma, delta epsilon zeta, eta theta iota, kappa lambda mu, nu xi omicron";
        List<String> pieces = SentenceSplitter.splitLong(longSentence, 40);
        assertTrue(pieces.size() > 1);
        for (String piece : pieces)
        {
            assertTrue(piece.length() <= 40, piece);
        }
        assertEquals(longSentence.replace(", ", ",").replace(" ", ""), String.join("", pieces).replace(" ", ""));
    }

    @Test
    @DisplayName("a short sentence is not cut by splitLong")
    void splitLongKeepsShortSentence()
    {
        assertEquals(List.of("A short one."), SentenceSplitter.splitLong("A short one.", 400));
    }
}
