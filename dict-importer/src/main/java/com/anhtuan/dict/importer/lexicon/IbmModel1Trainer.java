package com.anhtuan.dict.importer.lexicon;

import com.anhtuan.dict.core.lexicon.LexiconFormat;
import com.anhtuan.dict.core.lexicon.LexiconWriter;
import com.anhtuan.dict.core.nlp.TextNormalizer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

// Học bảng xác suất dịch từ bằng IBM Model 1 (thuật toán EM) trên kho câu song ngữ.
// Ban đầu mọi cặp (từ Anh, âm tiết Việt) trong cùng một câu có xác suất như nhau; mỗi vòng
// chia "công trạng" cho từng cặp theo xác suất hiện tại rồi cộng dồn thành xác suất mới.
//
// Ba cách thu nhỏ bài toán (quan trọng về bộ nhớ):
//  - Chỉ học từ cần thiết: phía Anh giới hạn ở mục từ, phía Việt ở từ vựng của các nghĩa
//    (~24.500 âm tiết); bảng đầy đủ sẽ gần một tỷ ô.
//  - Giữ một ô "khác" (id 0) ở cả hai phía thay vì vứt từ ngoài từ vựng, vì vứt đi sẽ làm
//    mẫu số sai và xác suất bị thổi phồng.
//  - Bảng băm địa chỉ mở trên mảng nguyên thủy; HashMap<Long, Float> với ~20 triệu phần tử
//    sẽ tốn vài GB chỉ cho boxing.
public final class IbmModel1Trainer
{

    // maxSentences: số cặp câu tối đa; lấy rải đều cả kho vì phụ đề được sắp theo từng bộ phim.
    // maxLength: bỏ câu quá dài (câu 60 từ sinh nhiều cặp sai và tốn bộ nhớ).
    // iterations: số vòng EM; đo thực tế thì qua vòng 4 kết quả gần như không đổi.
    // topK: số bản dịch giữ lại cho mỗi từ. tableBits: bảng băm có 2^tableBits ô.
    public record Config(int maxSentences, int maxLength, int iterations, int topK, int tableBits)
    {
        public static Config defaults()
        {
            return new Config(1_200_000, 20, 4, 16, 24);
        }
    }

    public record Result(List<LexiconWriter.EnglishWord> words, List<String> viVocabulary, int sentenceUsed,
            int pairCount, double fillRatio)
    {
    }

    private IbmModel1Trainer()
    {
    }

    private static final class IntSeq
    {
        private int[] a = new int[1 << 16];
        private int n;

        void add(int v)
        {
            if (n == a.length)
                a = Arrays.copyOf(a, a.length * 2);
            a[n++] = v;
        }

        int size()
        {
            return n;
        }

        int get(int i)
        {
            return a[i];
        }
    }

    // Bảng băm địa chỉ mở: cặp (từ Anh, âm tiết Việt) -> chỉ số ô.
    // Khóa lưu dưới dạng key + 1 để giá trị 0 nghĩa là "ô trống".
    private static final class PairTable
    {
        private final long[] keys;
        private final int mask;
        private int size;

        PairTable(int bits)
        {
            this.keys = new long[1 << bits];
            this.mask = (1 << bits) - 1;
        }

        private int slotOf(long key)
        {
            long k = key + 1;
            int i = (int) (mix(key) & mask);
            while (true)
            {
                long cur = keys[i];
                if (cur == 0)
                    return ~i; // ô trống -> trả về bù của vị trí
                if (cur == k)
                    return i;
                i = (i + 1) & mask;
            }
        }

        // Trả về chỉ số ô, thêm mới nếu chưa có; -1 nếu bảng đã đầy.
        int put(long key)
        {
            int s = slotOf(key);
            if (s >= 0)
                return s;
            if (size * 10 >= keys.length * 7)
                return -1; // hệ số tải 0,7 -> ngừng nhận thêm
            int i = ~s;
            keys[i] = key + 1;
            size++;
            return i;
        }

        int find(long key)
        {
            int s = slotOf(key);
            return s >= 0 ? s : -1;
        }

        long keyAt(int slot)
        {
            return keys[slot] - 1;
        }

        boolean used(int slot)
        {
            return keys[slot] != 0;
        }

        int size()
        {
            return size;
        }

        int capacity()
        {
            return keys.length;
        }

        private static long mix(long z)
        { // splitmix64, trộn bit cho đều
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return z ^ (z >>> 31);
        }
    }

    public static Result train(Path enFile, Path viFile, Set<String> allowedEn, Set<String> allowedVi, Config cfg,
            Consumer<String> log)
    {
        // 1. Đánh số từ vựng; id 0 ở cả hai phía dành cho "từ ngoài từ vựng".
        List<String> enVocab = new ArrayList<>(new TreeSet<>(allowedEn));
        List<String> viVocab = new ArrayList<>(new TreeSet<>(allowedVi));
        Map<String, Integer> enId = new HashMap<>(enVocab.size() * 2);
        Map<String, Integer> viId = new HashMap<>(viVocab.size() * 2);
        for (int i = 0; i < enVocab.size(); i++)
            enId.put(enVocab.get(i), i + 1);
        for (int i = 0; i < viVocab.size(); i++)
            viId.put(viVocab.get(i), i + 1);
        log.accept(String.format("vocabulary: %,d English words / %,d Vietnamese syllables", enVocab.size(),
                viVocab.size()));

        // 2. Đọc kho và mã hóa thành mảng int.
        long totalLines = countLines(enFile);
        int stride = (int) Math.max(1, totalLines / cfg.maxSentences());
        log.accept(String.format("corpus has %,d sentence pairs, taking every %d-th -> at most %,d pairs", totalLines,
                stride, cfg.maxSentences()));

        IntSeq enTokens = new IntSeq();
        IntSeq viTokens = new IntSeq();
        IntSeq enStart = new IntSeq();
        IntSeq viStart = new IntSeq();
        int used = 0;

        try (BufferedReader en = reader(enFile);
                BufferedReader vi = reader(viFile))
        {
            String le, lv;
            long lineNo = 0;
            while ((le = en.readLine()) != null && (lv = vi.readLine()) != null)
            {
                if (lineNo++ % stride != 0)
                    continue;
                if (used >= cfg.maxSentences())
                    break;

                List<String> e = englishTokens(le);
                List<String> v = TextNormalizer.splitTokens(lv);
                if (e.isEmpty() || v.isEmpty())
                    continue;
                if (e.size() > cfg.maxLength() || v.size() > cfg.maxLength())
                    continue;

                enStart.add(enTokens.size());
                viStart.add(viTokens.size());
                enTokens.add(0); // ô NULL, luôn có mặt trong mọi câu
                for (String t : e)
                    enTokens.add(enId.getOrDefault(t, 0));
                for (String t : v)
                    viTokens.add(viId.getOrDefault(t, 0));
                used++;
            }
        }
        catch (IOException ex)
        {
            throw new UncheckedIOException("cannot read parallel corpus", ex);
        }
        enStart.add(enTokens.size());
        viStart.add(viTokens.size());
        log.accept(
                String.format("loaded %,d sentence pairs (%,d + %,d tokens)", used, enTokens.size(), viTokens.size()));

        // 3. Ghi nhận mọi cặp từ cùng xuất hiện.
        PairTable table = new PairTable(cfg.tableBits());
        boolean full = false;
        for (int s = 0; s < used; s++)
        {
            int ea = enStart.get(s), eb = enStart.get(s + 1);
            int va = viStart.get(s), vb = viStart.get(s + 1);
            for (int i = ea; i < eb; i++)
            {
                long hi = ((long) enTokens.get(i)) << 32;
                for (int j = va; j < vb; j++)
                {
                    if (table.put(hi | viTokens.get(j)) < 0)
                    {
                        full = true;
                        break;
                    }
                }
                if (full)
                    break;
            }
            if (full)
                break;
        }
        double fill = (double) table.size() / table.capacity();
        log.accept(String.format("word pairs: %,d (table %.0f%% full)%s", table.size(), fill * 100,
                full ? "  ** TABLE FULL, result truncated **" : ""));

        // 4. Vòng lặp EM.
        float[] t = new float[table.capacity()];
        float[] count = new float[table.capacity()];
        double[] total = new double[enVocab.size() + 1];
        for (int i = 0; i < t.length; i++)
            if (table.used(i))
                t[i] = 1f;

        int[] slots = new int[(cfg.maxLength() + 1) * cfg.maxLength()];
        for (int iter = 1; iter <= cfg.iterations(); iter++)
        {
            long t0 = System.nanoTime();
            Arrays.fill(count, 0f);
            Arrays.fill(total, 0);

            for (int s = 0; s < used; s++)
            {
                int ea = enStart.get(s), eb = enStart.get(s + 1);
                int va = viStart.get(s), vb = viStart.get(s + 1);
                int nEn = eb - ea, nVi = vb - va;

                for (int j = 0; j < nVi; j++)
                {
                    int f = viTokens.get(va + j);
                    double denom = 0;
                    for (int i = 0; i < nEn; i++)
                    {
                        int slot = table.find((((long) enTokens.get(ea + i)) << 32) | f);
                        slots[j * nEn + i] = slot;
                        if (slot >= 0)
                            denom += t[slot];
                    }
                    if (denom <= 0)
                        continue;
                    for (int i = 0; i < nEn; i++)
                    {
                        int slot = slots[j * nEn + i];
                        if (slot < 0)
                            continue;
                        float c = (float) (t[slot] / denom);
                        count[slot] += c;
                        total[enTokens.get(ea + i)] += c;
                    }
                }
            }
            for (int i = 0; i < t.length; i++)
            {
                if (!table.used(i))
                    continue;
                int e = (int) (table.keyAt(i) >>> 32);
                double tot = total[e];
                t[i] = tot > 0 ? (float) (count[i] / tot) : 0f;
            }
            log.accept(String.format("  EM iteration %d/%d done (%,d ms)", iter, cfg.iterations(),
                    (System.nanoTime() - t0) / 1_000_000));
        }

        // 5. Lấy topK bản dịch cho mỗi từ tiếng Anh.
        int k = cfg.topK();
        int[][] topId = new int[enVocab.size() + 1][];
        float[][] topP = new float[enVocab.size() + 1][];
        int[] topN = new int[enVocab.size() + 1];

        for (int slot = 0; slot < t.length; slot++)
        {
            if (!table.used(slot) || t[slot] < LexiconFormat.MIN_PROBABILITY)
                continue;
            long key = table.keyAt(slot);
            int e = (int) (key >>> 32);
            int f = (int) (key & 0xFFFFFFFFL);
            if (e == 0 || f == 0)
                continue; // bỏ ô "ngoài từ vựng"
            if (topId[e] == null)
            {
                topId[e] = new int[k];
                topP[e] = new float[k];
            }
            insertTop(topId[e], topP[e], topN, e, f, t[slot], k);
        }

        List<LexiconWriter.EnglishWord> words = new ArrayList<>(enVocab.size());
        long pairs = 0;
        for (int e = 1; e <= enVocab.size(); e++)
        {
            if (topId[e] == null || topN[e] == 0)
                continue;
            List<String> tokens = new ArrayList<>(topN[e]);
            List<Double> probs = new ArrayList<>(topN[e]);
            for (int i = 0; i < topN[e]; i++)
            {
                tokens.add(viVocab.get(topId[e][i] - 1));
                probs.add((double) topP[e][i]);
            }
            words.add(new LexiconWriter.EnglishWord(enVocab.get(e - 1), tokens, probs));
            pairs += topN[e];
        }
        log.accept(String.format("kept %,d words with translations, %,d pairs", words.size(), pairs));
        return new Result(words, viVocab, used, (int) pairs, fill);
    }

    // Chèn một ứng viên vào danh sách topK đang giữ cho từ e (chèn trực tiếp).
    private static void insertTop(int[] ids, float[] ps, int[] counts, int e, int f, float p, int k)
    {
        int n = counts[e];
        if (n < k)
        {
            int i = n - 1;
            while (i >= 0 && ps[i] < p)
            {
                ps[i + 1] = ps[i];
                ids[i + 1] = ids[i];
                i--;
            }
            ps[i + 1] = p;
            ids[i + 1] = f;
            counts[e] = n + 1;
            return;
        }
        if (p <= ps[k - 1])
            return;
        int i = k - 2;
        while (i >= 0 && ps[i] < p)
        {
            ps[i + 1] = ps[i];
            ids[i + 1] = ids[i];
            i--;
        }
        ps[i + 1] = p;
        ids[i + 1] = f;
    }

    // Token tiếng Anh: chữ cái, chữ số, dấu nháy; đã lowercase.
    static List<String> englishTokens(String line)
    {
        List<String> out = new ArrayList<>(16);
        StringBuilder cur = new StringBuilder(16);
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'')
                cur.append(Character.toLowerCase(c));
            else
                if (!cur.isEmpty())
            {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (!cur.isEmpty())
            out.add(cur.toString());
        return out;
    }

    private static BufferedReader reader(Path p) throws IOException
    {
        return Files.newBufferedReader(p, StandardCharsets.UTF_8);
    }

    private static long countLines(Path p)
    {
        try (BufferedReader r = reader(p))
        {
            long n = 0;
            while (r.readLine() != null)
                n++;
            return n;
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot count lines of " + p, e);
        }
    }

    static String lower(String s)
    {
        return s.toLowerCase(Locale.ROOT);
    }
}
