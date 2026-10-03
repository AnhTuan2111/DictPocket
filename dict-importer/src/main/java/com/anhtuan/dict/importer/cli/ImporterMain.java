package com.anhtuan.dict.importer.cli;

import com.anhtuan.dict.core.index.IndexFormat;
import com.anhtuan.dict.core.index.IndexWriter;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.pack.PackWriter;
import com.anhtuan.dict.core.source.DictSource;
import com.anhtuan.dict.core.source.SourceCatalog;
import com.anhtuan.dict.core.spi.DictParser;
import com.anhtuan.dict.importer.parser.TsvDictParser;
import com.anhtuan.dict.importer.parser.AnhViet109KParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

// CLI dựng dữ liệu, chỉ chạy lúc build, không nằm trong app cuối.
// Lệnh: stats, dump, build, verify, query, lexicon (xem usage()).
public final class ImporterMain
{

    public static void main(String[] args) throws Exception
    {
        if (args.length < 2)
        {
            usage();
            System.exit(2);
        }
        String cmd = args[0];

        if (cmd.equals("verify"))
        {
            int failed = new VerifyCommand(Path.of(args[1])).run();
            System.exit(failed == 0 ? 0 : 1);
            return;
        }
        if (cmd.equals("lexicon"))
        {
            if (args.length < 5)
            {
                usage();
                System.exit(2);
            }
            int maxSentences = args.length >= 6 ? Integer.parseInt(args[5]) : 1_200_000;
            new LexiconCommand().run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]),
                    maxSentences);
            return;
        }
        if (cmd.equals("query"))
        {
            if (args.length < 4)
            {
                usage();
                System.exit(2);
            }
            new SearchCommand(Path.of(args[1])).run(args[2], String.join(" ", List.of(args).subList(3, args.length)));
            return;
        }

        Path source = Path.of(args[1]);
        if (!Files.isRegularFile(source))
        {
            System.err.println("File not found: " + source.toAbsolutePath());
            System.exit(2);
        }

        switch (cmd)
        {
            case "stats" -> stats(source);
            case "dump" -> {
                if (args.length < 3)
                {
                    usage();
                    System.exit(2);
                }
                dump(source, args[2]);
            }
            case "build" -> {
                if (args.length < 3)
                {
                    usage();
                    System.exit(2);
                }
                List<Path> extra = new ArrayList<>();
                for (int i = 3; i < args.length; i++)
                    extra.add(Path.of(args[i]));
                build(source, Path.of(args[2]), extra);
            }
            default -> {
                usage();
                System.exit(2);
            }
        }
    }

    // Số liệu thống kê để đối chiếu với số đo thủ công; lệch tức là parser sai.
    private static void stats(Path source)
    {
        AnhViet109KParser parser = new AnhViet109KParser();
        long entries = 0, senses = 0, glosses = 0, examples = 0, idioms = 0, multiWord = 0;

        long t0 = System.nanoTime();
        try (Stream<Entry> stream = parser.parse(source, 0))
        {
            for (Entry e : (Iterable<Entry>) stream::iterator)
            {
                entries++;
                if (e.isMultiWord())
                    multiWord++;
                senses += e.senses().size();
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

        System.out.printf("entry        : %,d%n", entries);
        System.out.printf("  multi-word  : %,d%n", multiWord);
        System.out.printf("sense        : %,d%n", senses);
        System.out.printf("gloss        : %,d%n", glosses);
        System.out.printf("example      : %,d%n", examples);
        System.out.printf("idiom        : %,d%n", idioms);
        System.out.printf("bad lines    : %,d%n", parser.malformedLineCount());
        System.out.printf("time         : %,d ms%n", ms);
        parser.malformedSamples().forEach(s -> System.out.println("  ! " + s));
    }

    private static void dump(Path source, String headword)
    {
        AnhViet109KParser parser = new AnhViet109KParser();
        String target = TextNormalizer.normalizeHeadword(headword);
        try (Stream<Entry> stream = parser.parse(source, 0))
        {
            stream.filter(e -> e.headwordNorm().equals(target)).forEach(ImporterMain::print);
        }
    }

    // Các định dạng đọc được; thêm định dạng mới chỉ cần thêm một dòng ở đây.
    private static List<DictParser> parsers()
    {
        return List.of(new AnhViet109KParser(), new TsvDictParser());
    }

    // Sinh dict.pack, các index và sources.tsv vào outDir từ một hoặc nhiều nguồn.
    private static void build(Path source, Path outDir, List<Path> extraSources) throws Exception
    {
        Files.createDirectories(outDir);

        List<Path> allSources = new ArrayList<>();
        allSources.add(source);
        allSources.addAll(extraSources);

        long t0 = System.nanoTime();
        List<Entry> entries = new ArrayList<>(120_000);
        List<DictSource> catalog = new ArrayList<>(allSources.size());

        for (int id = 0; id < allSources.size(); id++)
        {
            Path file = allSources.get(id);
            if (!Files.isRegularFile(file))
            {
                System.err.println("Skipping, file not found: " + file);
                continue;
            }
            DictParser parser = parsers().stream().filter(pp -> pp.canParse(file)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unrecognized format: " + file));

            System.out.println("Reading " + file.getFileName() + "  [" + parser.formatId() + "] ...");
            int before = entries.size();
            try (Stream<Entry> stream = parser.parse(file, id))
            {
                stream.forEach(entries::add);
            }
            int count = entries.size() - before;
            long malformed = parser instanceof AnhViet109KParser av ? av.malformedLineCount() : 0;
            System.out.printf("  %,d entry%s%n", count,
                    malformed > 0 ? String.format(", %,d bad lines", malformed) : "");
            // Nguồn phụ truyền thêm trên dòng lệnh phải ưu tiên hơn từ điển nền (id 0);
            // nếu priority = id thì từ điển nền luôn thắng và bảng thuật ngữ vô nghĩa.
            int priority = id == 0 ? allSources.size() - 1 : id - 1;
            catalog.add(new DictSource(id, displayName(file, parser.formatId()), parser.formatId(), file.getFileName().toString(), count,
                    true, priority));
        }
        System.out.printf("  total %,d entries from %d sources, %,d ms%n", entries.size(), catalog.size(), ms(t0));

        System.out.println("Writing dict.pack ...");
        long t1 = System.nanoTime();
        PackWriter.Stats packStats = PackWriter.write(outDir.resolve("dict.pack"), entries);
        System.out.printf("  %,d entries / %,d keys / %,d blocks%n", packStats.entryCount(), packStats.keyCount(),
                packStats.blockCount());
        System.out.printf("  %s (raw %s, compression ratio %.1f%%), %,d ms%n", mb(packStats.fileSize()),
                mb(packStats.rawSize()), packStats.compressionRatio() * 100, ms(t1));

        System.out.println("Writing index ...");
        long t2 = System.nanoTime();
        List<IndexWriter.Stats> idx = IndexWriter.build(outDir, entries);
        for (IndexWriter.Stats s : idx)
        {
            System.out.printf("  %-14s %,7d terms / %,10d posting pairs / %s%n", s.fileName(), s.termCount(),
                    s.postingPairs(), mb(s.fileSize()));
        }
        System.out.printf("  %,d ms%n", ms(t2));

        java.nio.file.Path wordList = outDir.resolve(com.anhtuan.dict.core.nlp.ViCompounds.FILE_NAME);
        long wordListSize = Files.exists(wordList) ? Files.size(wordList) : 0;
        System.out.printf("  %-14s %,7d Vietnamese compounds / %s%n", com.anhtuan.dict.core.nlp.ViCompounds.FILE_NAME,
                com.anhtuan.dict.core.nlp.ViCompounds.loadIfPresent(wordList).size(), mb(wordListSize));

        SourceCatalog.of(catalog).save(outDir.resolve(SourceCatalog.FILE_NAME));
        System.out.printf("  %-14s %,7d sources%n", SourceCatalog.FILE_NAME, catalog.size());

        long total = packStats.fileSize() + wordListSize;
        for (IndexWriter.Stats s : idx)
            total += s.fileSize();
        System.out.printf("TOTAL DATA   : %s  (budget: <= 11 MB)%n", mb(total));
    }

    private static void print(Entry e)
    {
        System.out.println("@ " + e.headword() + (e.ipa() == null ? "" : "  /" + e.ipa() + "/"));
        for (var s : e.senses())
        {
            System.out.println("  * " + (s.pos() == null ? "(no part of speech)" : s.pos()));
            for (String g : s.glosses())
                System.out.println("      - " + g);
            for (var ex : s.examples())
            {
                System.out.println("      = " + ex.en() + "  ==>  " + (ex.vi() == null ? "(no translation)" : ex.vi()));
            }
        }
        for (var i : e.idioms())
        {
            System.out.println("  ! " + i.phrase());
            for (String g : i.glosses())
                System.out.println("      - " + g);
            for (var ex : i.examples())
            {
                System.out.println("      = " + ex.en() + "  ==>  " + (ex.vi() == null ? "(no translation)" : ex.vi()));
            }
        }
        System.out.println();
    }

    // Tên hiển thị cho người dùng: dòng "# name: ..." đầu file nguồn phụ, nếu không có thì lấy tên file bỏ đuôi.
    private static String displayName(Path file, String formatId)
    {
        if ("anhviet109k".equals(formatId))
        {
            return "Anh-Việt 109K";
        }
        try (Stream<String> lines = Files.lines(file))
        {
            var declared = lines.limit(10).filter(l -> l.startsWith("# name:")).findFirst();
            if (declared.isPresent())
            {
                return declared.get().substring("# name:".length()).trim();
            }
        }
        catch (IOException | UncheckedIOException e)
        {
            // Không đọc được thì dùng tên file
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static long ms(long startNanos)
    {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static String mb(long bytes)
    {
        return String.format("%.2f MB", bytes / 1024.0 / 1024.0);
    }

    private static void usage()
    {
        System.err.println("""
                Usage:
                  stats  <file.txt>              print statistics
                  dump   <file.txt> <headword>   print one entry
                  build  <file.txt> <outDir> [source2 source3 ...]
                                                 generate dict.pack + """ + IndexFormat.VI_INDEX + " + "
                + IndexFormat.VI_NODIAC_INDEX + " + " + IndexFormat.TRIGRAM_INDEX + """

                          verify <outDir>                run the acceptance checks
                          query  <outDir> en|vi|sent <query>      inspect lookup results
                          lexicon <dict.txt> <corpus.en> <corpus.vi> <outDir> [maxSentences]
                                                         learn the word translation table
                        """);
    }

    private ImporterMain()
    {
    }
}
