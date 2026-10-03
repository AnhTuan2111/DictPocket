package com.anhtuan.dict.importer.cli;

import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.lexicon.LexiconFormat;
import com.anhtuan.dict.core.lexicon.LexiconWriter;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.importer.lexicon.IbmModel1Trainer;
import com.anhtuan.dict.importer.parser.AnhViet109KParser;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

// Học bảng xác suất dịch từ từ kho câu song ngữ, ghi ra lex.bin. Chỉ chạy lúc build.
// Chỉ học các từ tiếng Anh là mục từ và các âm tiết tiếng Việt có trong nghĩa của từ điển;
// phần còn lại không bao giờ được tra đến nên học chỉ tốn bộ nhớ.
final class LexiconCommand
{

    private final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
            StandardCharsets.UTF_8);

    void run(Path dictFile, Path corpusEn, Path corpusVi, Path outDir, int maxSentences)
    {
        out.println("== Reading the dictionary to find which words to learn ==");
        Set<String> allowedEn = new HashSet<>(120_000);
        Set<String> allowedVi = new HashSet<>(40_000);

        AnhViet109KParser parser = new AnhViet109KParser();
        try (Stream<Entry> stream = parser.parse(dictFile, 0))
        {
            for (Entry e : (Iterable<Entry>) stream::iterator)
            {
                collectEnglish(allowedEn, e.headwordNorm());
                for (Idiom idiom : e.idioms())
                {
                    // Cụm thành ngữ cũng đóng góp từ đơn: "to give up" -> give, up
                    for (String w : TextNormalizer.normalizeHeadword(idiom.phrase()).split(" "))
                    {
                        collectEnglish(allowedEn, w);
                    }
                    for (String g : idiom.glosses())
                        allowedVi.addAll(TextNormalizer.splitTokens(g));
                }
                for (Sense s : e.senses())
                {
                    for (String g : s.glosses())
                        allowedVi.addAll(TextNormalizer.splitTokens(g));
                }
            }
        }
        out.printf("  %,d English words / %,d Vietnamese syllables%n", allowedEn.size(), allowedVi.size());

        out.println();
        out.println("== Training IBM Model 1 ==");
        long t0 = System.nanoTime();
        IbmModel1Trainer.Config cfg = new IbmModel1Trainer.Config(maxSentences, 20, 4, 16, 24);
        IbmModel1Trainer.Result result = IbmModel1Trainer.train(corpusEn, corpusVi, allowedEn, allowedVi, cfg,
                s -> out.println("  " + s));
        out.printf("  total training time: %,d s%n", (System.nanoTime() - t0) / 1_000_000_000);

        out.println();
        out.println("== Writing " + LexiconFormat.FILE_NAME + " ==");
        Path target = outDir.resolve(LexiconFormat.FILE_NAME);
        LexiconWriter.Stats stats = LexiconWriter.write(target, result.words(), result.viVocabulary());
        out.printf("  %,d words / %,d pairs / %s%n", stats.enCount(), stats.pairCount(),
                ImporterMain.mb(stats.fileSize()));

        out.println();
        out.println("== Sample words ==");
        try (LexicalPrior prior = LexicalPrior.open(target))
        {
            for (String w : List.of("government", "plan", "school", "system", "work", "keep", "book", "old", "carry",
                    "decide", "number", "user", "weather"))
            {
                out.printf("  %-11s -> %s%n", w, String.join("  ", prior.topTranslations(w, 6)));
            }
        }
    }

    // Chỉ lấy từ đơn thuần chữ cái; cụm từ và ký hiệu không học được theo cách này.
    private static void collectEnglish(Set<String> target, String word)
    {
        if (word == null || word.isEmpty() || word.length() > 30)
            return;
        for (int i = 0; i < word.length(); i++)
        {
            char c = word.charAt(i);
            if (!(c >= 'a' && c <= 'z') && c != '\'')
                return;
        }
        target.add(word);
    }
}
