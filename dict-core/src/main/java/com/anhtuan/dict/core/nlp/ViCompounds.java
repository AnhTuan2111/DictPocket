package com.anhtuan.dict.core.nlp;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.TreeSet;

// Danh sách từ ghép tiếng Việt để gộp âm tiết lại khi đánh chỉ mục và khi tra: "chăm sóc" là
// một từ nhưng bị index thành hai âm tiết rời. Không dùng VnCoreNLP (GPL-3.0), danh sách
// được rút từ chính từ điển: phương án dịch dài 2-3 âm tiết lặp lại từ 3 mục trở lên
// (16.592 từ trên 200.060 nghĩa). expand() khớp TẤT CẢ chứ không chỉ cụm dài nhất, để
// "chăm sóc" vẫn tìm ra mục ghi "sự chăm sóc".
public final class ViCompounds
{

    // Ký tự nối các âm tiết của từ ghép, không bao giờ xuất hiện trong văn bản thật.
    public static final char JOIN = '_';

    public static final int MAX_SYLLABLES = 3;

    // File văn bản thuần để người dùng tự thêm từ.
    public static final String FILE_NAME = "vi-words.txt";

    private final Set<String> words;
    // Dạng bỏ dấu -> các dạng có dấu, dùng gợi ý khi gõ sai.
    private final Map<String, List<String>> byNoDiacritics;

    private ViCompounds(Set<String> words)
    {
        this.words = words;
        this.byNoDiacritics = new HashMap<>(words.size() * 2);
        for (String w : words)
        {
            byNoDiacritics.computeIfAbsent(TextNormalizer.removeDiacritics(w), k -> new ArrayList<>(2)).add(w);
        }
    }

    public static ViCompounds of(Collection<String> words)
    {
        return new ViCompounds(new HashSet<>(words));
    }

    public static ViCompounds empty()
    {
        return new ViCompounds(Set.of());
    }

    // File không tồn tại thì trả về bản rỗng, mọi thứ vẫn chạy bình thường.
    public static ViCompounds loadIfPresent(Path file)
    {
        if (!Files.isRegularFile(file))
            return empty();
        try
        {
            Set<String> words = new HashSet<>(32_768);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                String w = line.trim();
                if (!w.isEmpty() && w.charAt(0) != '#')
                    words.add(w);
            }
            return new ViCompounds(words);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    public static void write(Path file, Collection<String> words)
    {
        try
        {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null)
                Files.createDirectories(parent);
            List<String> sorted = new ArrayList<>(new TreeSet<>(words));
            Files.write(file, sorted, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    public boolean isAvailable()
    {
        return !words.isEmpty();
    }

    public int size()
    {
        return words.size();
    }

    public boolean contains(String compound)
    {
        return words.contains(compound);
    }

    // Trả về âm tiết gốc kèm các từ ghép nhận diện được:
    // [sự, chăm, sóc] -> [sự, chăm, sóc, sự_chăm_sóc, chăm_sóc]
    // Giữ âm tiết rời để gõ một chữ vẫn ra; từ ghép hiếm hơn nên IDF cao hơn hẳn.
    public List<String> expand(List<String> syllables)
    {
        if (words.isEmpty() || syllables.size() < 2)
            return syllables;
        List<String> out = new ArrayList<>(syllables.size() + 4);
        out.addAll(syllables);
        StringBuilder sb = new StringBuilder(24);
        for (int i = 0; i < syllables.size(); i++)
        {
            for (int n = 2; n <= MAX_SYLLABLES && i + n <= syllables.size(); n++)
            {
                sb.setLength(0);
                for (int k = 0; k < n; k++)
                {
                    if (k > 0)
                        sb.append(' ');
                    sb.append(syllables.get(i + k));
                }
                String phrase = sb.toString();
                if (words.contains(phrase))
                    out.add(phrase.replace(' ', JOIN));
            }
        }
        return out;
    }

    // Đường tắt dùng ở cả lúc build lẫn lúc tra.
    public List<String> tokenize(String text)
    {
        return expand(TextNormalizer.splitTokens(text));
    }

    // Đoán từ người dùng định gõ khi sai chính tả. Tìm theo âm tiết vẫn "chạy" nhưng ra rác
    // (gõ "cham sok" ra slow, sculp, shock). So trigram ký tự trên dạng đã bỏ dấu nên gõ thiếu
    // dấu vẫn khớp; quét toàn bộ (16.762 từ) vẫn đủ nhanh. Trả về rỗng nếu truy vấn vốn đã đúng.
    public List<String> suggest(String query, int limit)
    {
        if (words.isEmpty())
            return List.of();
        List<String> syllables = TextNormalizer.splitTokens(query);
        if (syllables.size() < 2)
            return List.of();

        String plain = TextNormalizer.removeDiacritics(String.join(" ", syllables));
        if (byNoDiacritics.containsKey(plain))
            return List.of(); // gõ đúng rồi, không gợi ý

        Set<String> queryGrams = new java.util.HashSet<>(trigrams(plain));
        if (queryGrams.isEmpty())
            return List.of();

        record Scored(String word, double score)
        {
        }
        List<Scored> scored = new ArrayList<>(16);
        for (Map.Entry<String, List<String>> e : byNoDiacritics.entrySet())
        {
            Set<String> grams = new java.util.HashSet<>(trigrams(e.getKey()));
            int matched = 0;
            for (String g : queryGrams)
                if (grams.contains(g))
                    matched++;
            double jaccard = (double) matched / (queryGrams.size() + grams.size() - matched);
            if (jaccard >= 0.5)
                scored.add(new Scored(e.getValue().getFirst(), jaccard));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<String> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (int i = 0; i < scored.size() && i < limit; i++)
            out.add(scored.get(i).word());
        return out;
    }

    private static List<String> trigrams(String text)
    {
        String padded = "$$" + text + "$$";
        List<String> out = new ArrayList<>(padded.length());
        for (int i = 0; i + 3 <= padded.length(); i++)
            out.add(padded.substring(i, i + 3));
        return out;
    }
}
