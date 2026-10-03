package com.anhtuan.dict.core.lexicon;

import com.anhtuan.dict.core.pack.Utf8Compare;
import com.anhtuan.dict.core.pack.VarInt;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Ghi lex.bin theo định dạng trong LexiconFormat.
public final class LexiconWriter
{

    private LexiconWriter()
    {
    }

    // Một từ tiếng Anh kèm danh sách bản dịch xếp theo xác suất giảm dần; probs cùng độ dài với viTokens,
    // viTokens phải nằm trong viVocabulary.
    public record EnglishWord(String word, List<String> viTokens, List<Double> probs)
    {
    }

    public record Stats(int enCount, int viCount, long pairCount, long fileSize)
    {
    }

    // viVocabulary là toàn bộ âm tiết tiếng Việt được phép xuất hiện, không cần sắp xếp.
    public static Stats write(Path target, List<EnglishWord> words, List<String> viVocabulary)
    {
        // Từ vựng tiếng Việt: sắp xếp để id = thứ tự, tra cứu bằng binary search.
        List<String> vi = new ArrayList<>(viVocabulary);
        vi.sort(Utf8Compare.COMPARATOR);
        java.util.Map<String, Integer> viId = new java.util.HashMap<>(vi.size() * 2);
        for (int i = 0; i < vi.size(); i++)
            viId.put(vi.get(i), i);

        List<EnglishWord> en = new ArrayList<>(words);
        en.sort((a, b) -> Utf8Compare.COMPARATOR.compare(a.word(), b.word()));

        // Dựng các vùng dữ liệu trong bộ nhớ trước, rồi ghi một lượt.
        ByteArrayOutputStream enKeys = new ByteArrayOutputStream(en.size() * 10);
        int[] enKeyOffsets = new int[en.size()];
        ByteArrayOutputStream postings = new ByteArrayOutputStream(en.size() * 40);
        int[] postingOffsets = new int[en.size()];
        int[] postingCounts = new int[en.size()];
        long pairCount = 0;

        for (int i = 0; i < en.size(); i++)
        {
            EnglishWord w = en.get(i);
            enKeyOffsets[i] = enKeys.size();
            byte[] kb = w.word().getBytes(StandardCharsets.UTF_8);
            enKeys.write(kb, 0, kb.length);
            enKeys.write(0);

            // Sắp theo id tăng dần để mã hóa delta; xác suất vẫn đọc đủ khi giải mã.
            List<int[]> entries = new ArrayList<>(w.viTokens().size());
            for (int k = 0; k < w.viTokens().size(); k++)
            {
                Integer id = viId.get(w.viTokens().get(k));
                if (id == null)
                    continue;
                int q = LexiconFormat.quantize(w.probs().get(k));
                if (q == 0)
                    continue;
                entries.add(new int[]{id, q});
            }
            entries.sort((a, b) -> Integer.compare(a[0], b[0]));

            postingOffsets[i] = postings.size();
            postingCounts[i] = entries.size();
            pairCount += entries.size();
            int prev = 0;
            for (int[] e : entries)
            {
                VarInt.write(postings, e[0] - prev);
                postings.write(e[1] & 0xFF);
                postings.write((e[1] >>> 8) & 0xFF);
                prev = e[0];
            }
        }

        ByteArrayOutputStream viKeys = new ByteArrayOutputStream(vi.size() * 8);
        int[] viKeyOffsets = new int[vi.size()];
        for (int i = 0; i < vi.size(); i++)
        {
            viKeyOffsets[i] = viKeys.size();
            byte[] kb = vi.get(i).getBytes(StandardCharsets.UTF_8);
            viKeys.write(kb, 0, kb.length);
            viKeys.write(0);
        }

        byte[] enKeyBytes = enKeys.toByteArray();
        byte[] viKeyBytes = viKeys.toByteArray();
        byte[] postingBytes = postings.toByteArray();

        long enKeysOffset = LexiconFormat.HEADER_SIZE;
        long enPtrOffset = enKeysOffset + enKeyBytes.length;
        long viKeysOffset = enPtrOffset + (long) en.size() * LexiconFormat.EN_PTR_SIZE;
        long viPtrOffset = viKeysOffset + viKeyBytes.length;
        long postingsBase = viPtrOffset + (long) vi.size() * LexiconFormat.VI_PTR_SIZE;

        int topK = 0;
        for (int n : postingCounts)
            topK = Math.max(topK, n);

        try
        {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null)
                Files.createDirectories(parent);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16))
            {
                ByteBuffer h = le(LexiconFormat.HEADER_SIZE);
                h.put(LexiconFormat.MAGIC);
                h.putInt(LexiconFormat.OFF_EN_COUNT, en.size());
                h.putInt(LexiconFormat.OFF_VI_COUNT, vi.size());
                h.putInt(LexiconFormat.OFF_TOP_K, topK);
                h.putLong(LexiconFormat.OFF_EN_KEYS, enKeysOffset);
                h.putLong(LexiconFormat.OFF_EN_PTRS, enPtrOffset);
                h.putLong(LexiconFormat.OFF_VI_KEYS, viKeysOffset);
                h.putLong(LexiconFormat.OFF_VI_PTRS, viPtrOffset);
                out.write(h.array());

                out.write(enKeyBytes);

                ByteBuffer ptrs = le(en.size() * LexiconFormat.EN_PTR_SIZE);
                for (int i = 0; i < en.size(); i++)
                {
                    ptrs.putInt(enKeyOffsets[i]);
                    ptrs.putInt(postingCounts[i]);
                    ptrs.putLong(postingsBase + postingOffsets[i]);
                }
                out.write(ptrs.array());

                out.write(viKeyBytes);

                ByteBuffer viPtrs = le(vi.size() * LexiconFormat.VI_PTR_SIZE);
                for (int off : viKeyOffsets)
                    viPtrs.putInt(off);
                out.write(viPtrs.array());

                out.write(postingBytes);
            }
            return new Stats(en.size(), vi.size(), pairCount, Files.size(target));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    private static ByteBuffer le(int size)
    {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }
}
