package com.anhtuan.dict.importer.cli;

import com.anhtuan.dict.core.index.IndexFormat;
import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.lexicon.LexiconFormat;
import com.anhtuan.dict.core.index.InvertedIndex;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.nlp.ViCompounds;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.service.DictionaryGlossEngine;
import com.anhtuan.dict.core.service.LookupService;
import com.anhtuan.dict.core.service.ReverseSearchService;
import com.anhtuan.dict.core.service.RuleBasedTranslationEngine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

// Soi dữ liệu đã build từ dòng lệnh, không cần mở UI (query <outDir> en|vi|sent <truy vấn>).
// In điểm từng ứng viên để gỡ lỗi xếp hạng.
final class SearchCommand
{

    private final Path dataDir;
    private final PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
            StandardCharsets.UTF_8);

    SearchCommand(Path dataDir)
    {
        this.dataDir = dataDir;
    }

    void run(String mode, String query)
    {
        try (PackReader pack = PackReader.open(dataDir.resolve("dict.pack"));
                InvertedIndex vi = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_INDEX));
                InvertedIndex viNo = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_NODIAC_INDEX));
                InvertedIndex tri = InvertedIndex.open(dataDir.resolve(IndexFormat.TRIGRAM_INDEX)))
        {

            var catalog = com.anhtuan.dict.core.source.SourceCatalog.loadOrDefault(
                    dataDir.resolve(com.anhtuan.dict.core.source.SourceCatalog.FILE_NAME), "default",
                    pack.entryCount());
            LookupService lookup = new LookupService(pack, catalog);
            switch (mode)
            {
                case "en" -> {
                    var entries = lookup.lookupAll(query);
                    if (entries.isEmpty())
                    {
                        out.println("No match for \"" + query + "\". Closest suggestions:");
                        new ReverseSearchService(pack, vi, viNo, tri).fuzzyEnglish(query, 5).forEach(
                                h -> out.printf("  %-20s %.3f  %s%n", h.display(), h.score(), h.matchedGloss()));
                    }
                    else
                    {
                        entries.forEach(e ->
                        {
                            out.println("@ " + e.headword() + (e.ipa() == null ? "" : "  /" + e.ipa() + "/"));
                            e.senses().forEach(s ->
                            {
                                out.println("  * " + (s.pos() == null ? "?" : s.pos()));
                                s.glosses().forEach(g -> out.println("      - " + g));
                            });
                            e.idioms().forEach(i -> out.println("  ! " + i.phrase() + " = "
                                    + (i.glosses().isEmpty() ? "" : i.glosses().getFirst())));
                        });
                    }
                }
                case "vi" -> {
                    ViCompounds compounds = ViCompounds.loadIfPresent(dataDir.resolve(ViCompounds.FILE_NAME));
                    var search = new ReverseSearchService(pack, vi, viNo, tri, compounds,
                            LexicalPrior.openIfPresent(dataDir.resolve(LexiconFormat.FILE_NAME)));
                    search.setCatalog(catalog);
                    List<ReverseSearchService.Hit> hits = search.searchVietnamese(query, 15);
                    List<String> suggestions = search.suggestVietnamese(query, 3);
                    if (!suggestions.isEmpty())
                    {
                        out.println("  Did you mean: " + String.join(" / ", suggestions) + " ?");
                    }
                    if (hits.isEmpty())
                        out.println("no results");
                    for (int i = 0; i < hits.size(); i++)
                    {
                        ReverseSearchService.Hit h = hits.get(i);
                        out.printf(Locale.ROOT, "  %2d. %-24s %8.3f  %s%n", i + 1, h.display(), h.score(),
                                h.matchedGloss());
                    }
                }
                case "sent" -> {
                    var prior = LexicalPrior.openIfPresent(dataDir.resolve(LexiconFormat.FILE_NAME));
                    var glossEngine = new DictionaryGlossEngine(lookup, pack.multiWordStarters(), prior);
                    var ruleEngine = new RuleBasedTranslationEngine(glossEngine, lookup, prior);
                    out.println("  (probability table: "
                            + (prior.isAvailable() ? String.format("%,d words", prior.wordCount()) : "NONE") + ")");

                    out.println("  TRANSLATION (rule-based):");
                    out.println("    " + ruleEngine.translate(query).getFirst().displayGloss());
                    out.println();
                    out.println("  PER-SEGMENT GLOSS:");
                    for (Segment s : glossEngine.translate(query))
                    {
                        if (s.sourceText().isBlank())
                            continue;
                        out.printf("    %-18s %-9s %s%n", s.sourceText(), s.kind(),
                                s.displayGloss() == null ? "" : s.displayGloss());
                    }
                }
                default -> out.println("mode must be en | vi | sent");
            }
        }
    }
}
