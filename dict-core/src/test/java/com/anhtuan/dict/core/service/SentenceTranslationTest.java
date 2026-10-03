package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.pack.PackWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static com.anhtuan.dict.core.TestEntries.entry;
import static com.anhtuan.dict.core.TestEntries.idiom;
import static com.anhtuan.dict.core.TestEntries.sense;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Từ điển riêng cho bộ test, chép lại ba cái bẫy thật của nguồn: @school có hai nhóm danh từ mà
// "đàn cá" đứng trước, @reading chỉ có từ loại danh từ, và nghĩa nào cũng là chùm đồng nghĩa dài.
class SentenceTranslationTest
{

    @TempDir
    Path tmp;

    private PackReader pack;
    private RuleBasedTranslationEngine engine;

    private static List<Entry> dictionary()
    {
        return List.of(entry("go", "gou", List.of(sense("động từ", "đi, đi đến, đi tới")), List.of()),
                entry("market", null, List.of(sense("danh từ", "chợ, thị trường")), List.of()),
                entry("system", null, List.of(sense("danh từ", "hệ thống, mạng lưới")), List.of()),
                entry("old", null, List.of(sense("tính từ", "già, cũ, xưa")), List.of()),
                entry("book", null,
                        List.of(sense("danh từ", "sách, quyển sách"), sense("động từ", "đặt trước, giữ chỗ")),
                        List.of()),
                entry("good", null, List.of(sense("tính từ", "tốt, hay, lành")), List.of()),
                entry("job", null, List.of(sense("danh từ", "việc, việc làm, công việc")), List.of()),
                entry("give", "giv", List.of(sense("động từ", "cho, biếu, tặng")),
                        List.of(idiom("to give up", "bỏ, từ bỏ"))),
                entry("work", null, List.of(sense("danh từ", "sự làm việc"), sense("động từ", "làm việc, hoạt động")),
                        List.of()),
                // Bẫy 1: nhóm nghĩa ít được viết kỹ đứng trước nhóm hay dùng.
                entry("school", null,
                        List.of(sense("danh từ", "đàn cá", "bầy cá"),
                                sense("danh từ", "trường học", "học đường", "trường sở", "buổi học")),
                        List.of()),
                // Bẫy 2: dạng chia có mục riêng và chỉ mang từ loại danh từ.
                entry("reading", null, List.of(sense("danh từ", "sự đọc, sự đọc sách")), List.of()),
                entry("read", null, List.of(sense("động từ", "đọc, đọc sách")), List.of()),
                // Bẫy 3: cụm nhiều từ là danh từ, không phải cụm động từ.
                entry("use case", null, List.of(sense("danh từ", "ca sử dụng")), List.of()));
    }

    @BeforeEach
    void setUp()
    {
        Path file = tmp.resolve("dict.pack");
        PackWriter.write(file, dictionary());
        pack = PackReader.open(file);
        LookupService lookup = new LookupService(pack);
        engine = new RuleBasedTranslationEngine(new DictionaryGlossEngine(lookup, pack.multiWordStarters()), lookup);
    }

    @AfterEach
    void tearDown()
    {
        pack.close();
    }

    private String translate(String sentence)
    {
        var segments = engine.translate(sentence);
        assertEquals(1, segments.size(), "a whole-sentence engine must return exactly one segment");
        assertEquals(SegmentKind.TRANSLATED, segments.getFirst().kind());
        return segments.getFirst().displayGloss();
    }

    @Test
    @DisplayName("noun phrase order is reversed: the old system -> hệ thống cũ")
    void nounPhraseIsReordered()
    {
        // Mạo từ biến mất, tính từ ra sau danh từ: hai khác biệt lớn nhất giữa hai thứ tiếng.
        assertEquals("Hệ thống già", translate("The old system"));
        assertEquals("Sách này", translate("This book"));
    }

    @Test
    @DisplayName("possessive moves after the noun: his job -> việc của anh ấy")
    void possessiveMovesAfterNoun()
    {
        assertTrue(translate("He gave up his job").contains("việc của anh ấy"), translate("He gave up his job"));
    }

    @Test
    @DisplayName("past tense produces \"đã\"")
    void pastTenseAddsMarker()
    {
        assertEquals("Anh ấy đã đi đến chợ", translate("He went to the market"));
    }

    @Test
    @DisplayName("negation merges with the modal verb: could not -> không thể")
    void negationMergesWithModal()
    {
        assertTrue(translate("The system could not work").startsWith("Hệ thống không thể"),
                translate("The system could not work"));
    }

    @Test
    @DisplayName("sense chosen by part of speech: work after \"could not\" is a verb")
    void partOfSpeechDecidesTheMeaning()
    {
        assertTrue(translate("The system could not work").endsWith("làm việc"),
                "must pick the verb sense \"làm việc\", not the noun \"sự làm việc\"");
    }

    @Test
    @DisplayName("imperative: a sentence-initial word with a verb sense is a verb")
    void sentenceInitialWordIsImperative()
    {
        // Đề bài và tài liệu kỹ thuật gần như toàn câu mệnh lệnh; thiếu luật này "Book" ra "sách".
        assertEquals("Đặt trước chợ", translate("Book the market"));
    }

    @Test
    @DisplayName("verb list: items after the comma and \"and\" are verbs too")
    void verbListKeepsVerbSense()
    {
        // "work" sau dấu phẩy phải lấy nghĩa động từ "làm việc", không phải "sự làm việc".
        assertEquals("Đọc, làm việc, và đặt trước", translate("Read, work, and book"));
    }

    @Test
    @DisplayName("numerals precede the noun and are not looked up")
    void numeralsStayBeforeTheNoun()
    {
        // "four" không có trong bảng từ chức năng thì bị tra nguồn và ra "chứng khoán lãi 4 qịu".
        assertEquals("Bốn sách", translate("The four books"));
    }

    @Test
    @DisplayName("comparative: older -> già hơn")
    void comparativeAddsMarker()
    {
        // Lemmatizer cắt đuôi -er để tra từ điển nên nghĩa tra ra mất ý so sánh.
        assertEquals("Hệ thống già hơn", translate("The older system"));
    }

    @Test
    @DisplayName("including is a preposition, not a modifying adjective")
    void participialPrepositionStaysBeforeItsObject()
    {
        // Từ điển ghi "including" là tính từ; để nguyên thì sắp lại danh ngữ đẩy nó ra sau danh từ
        // ("gồm cả việc" thành "việc gồm cả").
        assertEquals("Sách gồm cả việc", translate("The book including the job"));
    }

    @Test
    @DisplayName("multi-word nouns take part in noun phrase reordering")
    void multiWordNounJoinsNounPhraseReorder()
    {
        // Nếu coi mọi cụm nhiều từ là cụm động từ thì tính từ không được đẩy ra sau.
        assertEquals("Ca sử dụng tốt", translate("A good use case"));
    }

    @Test
    @DisplayName("the better-documented sense group wins: school -> trường học")
    void richerSenseWins()
    {
        // Theo thứ tự file thì ra "đàn cá": đúng nghĩa từ điển nhưng sai ý người dùng.
        assertTrue(translate("He went to school").contains("trường học"), translate("He went to school"));
    }

    @Test
    @DisplayName("is + V-ing -> \"đang\", falling back to the base form to get the verb sense")
    void progressiveFallsBackToLemma()
    {
        // "@reading" chỉ có từ loại danh từ ("sự đọc") nên phải lùi về "read".
        assertEquals("Anh ấy đang đọc sách", translate("He is reading a book"));
    }

    @Test
    @DisplayName("phrasal verbs are still recognized and still carry tense markers")
    void phrasalVerbKeepsWorking()
    {
        assertTrue(translate("He gave up his job").startsWith("Anh ấy đã bỏ"), translate("He gave up his job"));
    }

    @Test
    @DisplayName("one gloss line is split into separate candidates")
    void glossAlternativesAreSplit()
    {
        // Bảng xác suất chấm TỪNG phương án nên phải tách ra trước; phương án đầu chưa chắc đúng.
        assertEquals(List.of("cho", "biếu", "tặng", "ban"),
                RuleBasedTranslationEngine.alternatives("cho, biếu, tặng, ban"));
        assertEquals(List.of("loại a", "hạng nhất"),
                RuleBasedTranslationEngine.alternatives("(thông tục) loại a, hạng nhất"));
        assertTrue(RuleBasedTranslationEngine.alternatives(null).isEmpty());
    }

    @Test
    @DisplayName("a long gloss is cut down to the first candidate")
    void longGlossesAreShortened()
    {
        assertEquals("sách", RuleBasedTranslationEngine.shorten("sách, quyển sách"));
        assertEquals("giữ vững", RuleBasedTranslationEngine.shorten("giữ vững, giữ không cho đổ"));
        assertEquals("loại a", RuleBasedTranslationEngine.shorten("(thông tục) loại a, hạng nhất"));
    }

    @Test
    @DisplayName("function words are translated by rule, not by dictionary sense")
    void functionWordsBypassTheDictionary()
    {
        // Đúng cái bẫy ở "@he": nghĩa được viết kỹ nhất lại là "đàn ông, con đực".
        assertTrue(translate("He went to the market").startsWith("Anh ấy"));
        assertEquals("go", TextNormalizer.normalizeHeadword("Go"));
    }
}
