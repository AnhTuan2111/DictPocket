package com.anhtuan.dict.importer.cli;

import com.anhtuan.dict.core.index.IndexFormat;
import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.lexicon.LexiconFormat;
import com.anhtuan.dict.core.index.InvertedIndex;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.nlp.ViCompounds;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.service.DictionaryGlossEngine;
import com.anhtuan.dict.core.service.LookupService;
import com.anhtuan.dict.core.service.ReverseSearchService;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

// Chạy lại toàn bộ tiêu chí nghiệm thu trên dữ liệu thật và in pass/fail.
// Phần lớn lỗi ở đây không làm crash (sắp xếp/chuẩn hóa lệch một chút chỉ khiến thỉnh thoảng
// tra không ra từ), nên cần một lệnh khẳng định được "dữ liệu này đúng".
final class VerifyCommand
{

    private final Path dataDir;
    private final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
            StandardCharsets.UTF_8);

    private int passed;
    private int failed;

    VerifyCommand(Path dataDir)
    {
        this.dataDir = dataDir;
    }

    int run() throws Exception
    {
        Path pack = dataDir.resolve("dict.pack");
        if (!Files.isRegularFile(pack))
        {
            out.println("Not found: " + pack.toAbsolutePath() + " - run the build command first.");
            return 1;
        }

        long heapBefore = usedHeap();
        try (PackReader reader = PackReader.open(pack);
                InvertedIndex vi = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_INDEX));
                InvertedIndex viNo = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_NODIAC_INDEX));
                InvertedIndex tri = InvertedIndex.open(dataDir.resolve(IndexFormat.TRIGRAM_INDEX)))
        {

            long heapAfter = usedHeap();
            var catalog = com.anhtuan.dict.core.source.SourceCatalog.loadOrDefault(
                    dataDir.resolve(com.anhtuan.dict.core.source.SourceCatalog.FILE_NAME), "default",
                    reader.entryCount());
            LookupService lookup = new LookupService(reader, catalog);
            Set<String> starters = reader.multiWordStarters();
            LexicalPrior prior = LexicalPrior.openIfPresent(dataDir.resolve(LexiconFormat.FILE_NAME));
            DictionaryGlossEngine gloss = new DictionaryGlossEngine(lookup, starters, prior);
            ViCompounds compounds = ViCompounds.loadIfPresent(dataDir.resolve(ViCompounds.FILE_NAME));
            ReverseSearchService search = new ReverseSearchService(reader, vi, viNo, tri, compounds, prior);

            section("dict.pack");
            // Ngân sách theo số đo thật: nén theo block (để tra cứu ngẫu nhiên được) tốn
            // thêm ~20 điểm tỷ lệ nén so với nén cả file một lượt (gzip -9 = 3,98 MB).
            check("dict.pack <= 8,0 MB", Files.size(pack) <= 8.0 * 1024 * 1024, ImporterMain.mb(Files.size(pack)));
            // Tổng entry phụ thuộc số nguồn đã build; bất biến thật sự là nguồn 109K ra đúng
            // 108.854 mục và tổng pack bằng tổng các nguồn (lệch một mục là nguồn bị đọc thiếu).
            int fromMain = catalog.all().stream().filter(src -> "anhviet109k".equals(src.format()))
                    .mapToInt(src -> src.entries()).sum();
            check("109K source yields exactly 108,854 entries", fromMain == 108_854, String.format("%,d", fromMain));

            int declared = catalog.all().stream().mapToInt(src -> src.entries()).sum();
            check("pack total = sum of sources", reader.entryCount() == declared,
                    String.format("%,d entries from %d sources", reader.entryCount(), catalog.size()));
            check("KEYS >= 120,000 keys (incl. alias keys)", reader.keyCount() >= 120_000,
                    String.format("%,d keys for %,d entries", reader.keyCount(), reader.entryCount()));
            check("heap after opening pack < 5 MB", heapAfter - heapBefore < 5 * 1024 * 1024,
                    ImporterMain.mb(Math.max(0, heapAfter - heapBefore)));
            check("phraseStarters ~5,949 words", starters.size() > 4_000, String.format("%,d words", starters.size()));

            Entry give = reader.lookup("give up").orElse(null);
            check("lookup(\"give up\") -> entry @give  [khoa bi danh]",
                    give != null && give.headwordNorm().equals("give"),
                    give == null ? "not found" : "@" + give.headword());
            check("lookup(\"look after\") -> entry @look",
                    reader.lookup("look after").map(e -> e.headwordNorm().equals("look")).orElse(false),
                    reader.lookup("look after").map(Entry::headword).orElse("not found"));
            check("lookup(\"about to\") does NOT exist (source limitation)", reader.lookup("about to").isEmpty(),
                    "as expected from the source data");

            Entry about = reader.lookup("about").orElse(null);
            check("entry @about has ipa + >= 2 senses + idioms",
                    about != null && about.ipa() != null && about.senses().size() >= 2 && !about.idioms().isEmpty(),
                    about == null
                            ? "not found"
                            : "/" + about.ipa() + "/, " + about.senses().size() + " sense, " + about.idioms().size()
                                    + " idiom");

            double avgUs = benchLookup(reader);
            check("single lookup < 1 ms (10,000 random lookups)", avgUs < 1000,
                    String.format(Locale.ROOT, "average %.1f us", avgUs));

            section("index + Vietnamese->English");
            check("Vietnamese compound list present (vi-words.txt)", compounds.isAvailable(),
                    compounds.isAvailable()
                            ? String.format("%,d compounds", compounds.size())
                            : "missing - regenerate data with the build command");
            long viSize = Files.size(dataDir.resolve(IndexFormat.VI_INDEX));
            long triSize = Files.size(dataDir.resolve(IndexFormat.TRIGRAM_INDEX));
            check("vi.idx <= 2,4 MB", viSize <= 2.4 * 1024 * 1024, ImporterMain.mb(viSize));
            check("tri.idx <= 2,2 MB", triSize <= 2.2 * 1024 * 1024, ImporterMain.mb(triSize));
            long totalData = Files.size(pack) + viSize + triSize
                    + Files.size(dataDir.resolve(IndexFormat.VI_NODIAC_INDEX));
            check("total data <= 14.5 MB", totalData <= 14.5 * 1024 * 1024, ImporterMain.mb(totalData));

            checkSearch(search, "chăm sóc", List.of("care", "look after", "nurse"));
            checkSearch(search, "cham soc", List.of("care", "look after", "nurse"));
            checkSearch(search, "ngân hàng", List.of("bank"));

            long t0 = System.nanoTime();
            search.searchVietnamese("chăm sóc", 20);
            long searchMs = (System.nanoTime() - t0) / 1_000_000;
            check("search < 30 ms", searchMs < 30, searchMs + " ms");

            check("misspelled Vietnamese \"cham sok\" -> suggests \"chăm sóc\"",
                    search.suggestVietnamese("cham sok", 3).contains("chăm sóc"),
                    search.suggestVietnamese("cham sok", 3).toString());
            check("correct input gives NO suggestion", search.suggestVietnamese("chăm sóc", 3).isEmpty(),
                    "no spurious suggestion");
            checkSearch(search, "kế hoạch", List.of("plan"));
            checkSearch(search, "chính phủ", List.of("government"));
            checkSearch(search, "nghiên cứu", List.of("research"));

            List<ReverseSearchService.Hit> fuzzy = search.fuzzyEnglish("aboout", 5);
            check("misspelled \"aboout\" -> \"about\" ranked first",
                    !fuzzy.isEmpty() && fuzzy.getFirst().entry().headwordNorm().equals("about"),
                    fuzzy.isEmpty()
                            ? "no results"
                            : fuzzy.stream().limit(3).map(h -> h.entry().headword()).toList().toString());

            section("sentence translation");
            checkPhrase(gloss, "He gave up his job.", "give up", "gave up");
            checkPhrase(gloss, "She looks after them.", "look after", "looks after");
            checkLemma(gloss, "She went running yesterday.", "went", "running");
            checkAboutTo(gloss);
            checkOffsets(gloss, "He gave up his job.");

            section("rule-based sentence translation");
            check("word translation table present (lex.bin)", prior.isAvailable(),
                    prior.isAvailable()
                            ? String.format("%,d English words", prior.wordCount())
                            : "missing - run the lexicon command to generate");
            var sentenceEngine = new com.anhtuan.dict.core.service.RuleBasedTranslationEngine(gloss, lookup, prior);
            checkSentence(sentenceEngine, "She went to the market yesterday", new String[]{"Cô ấy", "đã", "chợ"});
            checkSentence(sentenceEngine, "The weather is very cold today", new String[]{"Thời tiết", "rất"});
            checkSentence(sentenceEngine, "We will not go to school tomorrow",
                    new String[]{"sẽ không", "trường học"});
            checkSentence(sentenceEngine, "This system does not work well", new String[]{"Hệ thống này", "không"});
            checkSentence(sentenceEngine, "The teacher gave me a very good book",
                    new String[]{"Giáo viên", "cho tôi", "rất tốt"});

            section("sentence translation performance");
            String twentyWords = "The government decided to carry out a new plan because the old "
                    + "system could not keep up with the growing number of users";
            long t1 = System.nanoTime();
            List<Segment> segs = gloss.translate(twentyWords);
            long transMs = (System.nanoTime() - t1) / 1_000_000;
            long resolved = segs.stream().filter(s -> s.kind() == SegmentKind.WORD || s.kind() == SegmentKind.PHRASE)
                    .count();
            long words = segs.stream().filter(s -> s.kind() != SegmentKind.PUNCT).count();
            check("24-word sentence translation < 50 ms", transMs < 50, transMs + " ms");
            check("resolved ratio >= 90%", resolved * 100 >= words * 90, resolved + "/" + words + " segments");
            out.println();
            out.println("  Input   : " + twentyWords);
            out.println("  Output  : " + sentenceEngine.translate(twentyWords).getFirst().displayGloss());

            out.println();
            out.printf("=== %d passed / %d failed ===%n", passed, failed);
        }
        return failed;
    }

    // Kiểm tra bản dịch CHỨA các mảnh bắt buộc thay vì so sánh nguyên văn: đổi một nghĩa trong
    // từ điển không làm hỏng test; thứ cần khẳng định là bộ luật (đảo trật tự, dấu hiệu thì...).
    private void checkSentence(com.anhtuan.dict.core.service.RuleBasedTranslationEngine engine, String english,
            String[] mustContain)
    {
        String vi = engine.translate(english).getFirst().displayGloss();
        boolean ok = true;
        for (String piece : mustContain)
            ok &= vi != null && vi.contains(piece);
        check("\"" + trim(english) + "\"", ok, vi);
    }

    private static String trim(String s)
    {
        return s.length() <= 34 ? s : s.substring(0, 31) + "...";
    }

    private void checkSearch(ReverseSearchService search, String query, List<String> expected)
    {
        List<ReverseSearchService.Hit> hits = search.searchVietnamese(query, 5);
        List<String> heads = hits.stream().map(h -> h.entry().headwordNorm()).toList();
        boolean ok = expected.stream().anyMatch(heads::contains);
        check("query \"" + query + "\" -> " + expected + " in top 5", ok, heads.toString());
    }

    private void checkPhrase(DictionaryGlossEngine gloss, String sentence, String expectedKey, String expectedSource)
    {
        List<Segment> segs = gloss.translate(sentence);
        Segment phrase = segs.stream().filter(s -> s.kind() == SegmentKind.PHRASE).findFirst().orElse(null);
        boolean ok = phrase != null && phrase.sourceText().equalsIgnoreCase(expectedSource)
                && phrase.displayGloss() != null;
        check("\"" + sentence + "\" recognizes phrase " + expectedKey, ok,
                phrase == null ? "no phrase recognized" : phrase.sourceText() + " = " + phrase.displayGloss());
    }

    private void checkLemma(DictionaryGlossEngine gloss, String sentence, String... words)
    {
        List<Segment> segs = gloss.translate(sentence);
        StringBuilder detail = new StringBuilder();
        boolean ok = true;
        for (String w : words)
        {
            Segment seg = segs.stream().filter(s -> s.sourceText().equalsIgnoreCase(w)).findFirst().orElse(null);
            boolean found = seg != null && seg.kind() == SegmentKind.WORD && !seg.candidates().isEmpty();
            ok &= found;
            detail.append(w).append(" -> ").append(found ? seg.candidates().getFirst().headword() : "FAILED")
                    .append("  ");
        }
        check("\"" + sentence + "\" lemmatized correctly", ok, detail.toString().trim());
    }

    private void checkAboutTo(DictionaryGlossEngine gloss)
    {
        List<Segment> segs = gloss.translate("He is about to leave.");
        boolean aboutAlone = segs.stream()
                .anyMatch(s -> s.sourceText().equals("about") && s.kind() == SegmentKind.WORD);
        boolean toAlone = segs.stream().anyMatch(s -> s.sourceText().equals("to") && s.kind() == SegmentKind.WORD);
        check("\"He is about to leave.\" splits about + to, no phrase", aboutAlone && toAlone,
                segs.stream().filter(s -> s.kind() != SegmentKind.PUNCT).map(Segment::sourceText).toList().toString());
    }

    // Bất biến quan trọng nhất: nối các segment lại phải ra đúng câu gốc.
    private void checkOffsets(DictionaryGlossEngine gloss, String sentence)
    {
        List<Segment> segs = gloss.translate(sentence);
        StringBuilder sb = new StringBuilder();
        boolean contiguous = true;
        int expectStart = 0;
        for (Segment s : segs)
        {
            if (s.startOffset() != expectStart)
                contiguous = false;
            expectStart = s.endOffset();
            sb.append(sentence, s.startOffset(), s.endOffset());
        }
        check("offsets cover the source with no gaps or overlaps",
                contiguous && sb.toString().equals(sentence) && expectStart == sentence.length(),
                contiguous ? "match" : "gap found");
    }

    private double benchLookup(PackReader reader)
    {
        Random rnd = new Random(42);
        List<String> sample = reader.prefixScan("a", 2000);
        if (sample.isEmpty())
            return Double.MAX_VALUE;
        for (int i = 0; i < 2000; i++)
            reader.lookup(sample.get(rnd.nextInt(sample.size()))); // warm-up
        long t0 = System.nanoTime();
        int n = 10_000;
        for (int i = 0; i < n; i++)
            reader.lookup(sample.get(rnd.nextInt(sample.size())));
        return (System.nanoTime() - t0) / 1000.0 / n;
    }

    private void section(String title)
    {
        out.println();
        out.println("--- " + title + " ---");
    }

    private void check(String name, boolean ok, String detail)
    {
        if (ok)
            passed++;
        else
            failed++;
        out.printf("  [%s] %-52s %s%n", ok ? "PASS" : "FAIL", name, detail == null ? "" : detail);
    }

    private static long usedHeap()
    {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
