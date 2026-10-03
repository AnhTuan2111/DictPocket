package com.anhtuan.dict.core.lexicon;

import com.anhtuan.dict.core.pack.Utf8Compare;
import com.anhtuan.dict.core.pack.VarInt;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// Đọc bảng xác suất dịch từ và chấm điểm một nghĩa tiếng Việt cho một từ tiếng Anh.
// Từ điển cho biết plan có thể là "kế hoạch", "sơ đồ", "đồ án", "mặt bằng", đều là danh từ nên luật ngữ pháp
// không chọn được; bảng này học từ 3,5 triệu cặp câu thật rằng "kế hoạch" thường hơn hẳn.
// Không phải mô hình nơ-ron mà là bảng tra tĩnh, học một lần lúc build bằng IBM Model 1; lúc chạy chỉ mmap và binary search.
// Vắng file cũng không sao: empty() trả về bản rỗng, mọi điểm bằng 0, engine dịch quay về cách chọn nghĩa cũ.
public final class LexicalPrior implements Closeable
{

    private final Arena arena;
    private final ByteBuffer buf;
    private final int enCount;
    private final int viCount;
    private final int enKeysBase;
    private final int enPtrBase;
    private final int viKeysBase;
    private final int viPtrBase;

    private LexicalPrior(Arena arena, ByteBuffer buf)
    {
        this.arena = arena;
        this.buf = buf;
        if (buf == null)
        {
            this.enCount = 0;
            this.viCount = 0;
            this.enKeysBase = 0;
            this.enPtrBase = 0;
            this.viKeysBase = 0;
            this.viPtrBase = 0;
            return;
        }
        byte[] magic = new byte[LexiconFormat.MAGIC.length];
        for (int i = 0; i < magic.length; i++)
            magic[i] = buf.get(i);
        if (!Arrays.equals(magic, LexiconFormat.MAGIC))
        {
            throw new IllegalStateException("not a lex.bin file (bad magic)");
        }
        this.enCount = buf.getInt(LexiconFormat.OFF_EN_COUNT);
        this.viCount = buf.getInt(LexiconFormat.OFF_VI_COUNT);
        this.enKeysBase = (int) buf.getLong(LexiconFormat.OFF_EN_KEYS);
        this.enPtrBase = (int) buf.getLong(LexiconFormat.OFF_EN_PTRS);
        this.viKeysBase = (int) buf.getLong(LexiconFormat.OFF_VI_KEYS);
        this.viPtrBase = (int) buf.getLong(LexiconFormat.OFF_VI_PTRS);
    }

    public static LexicalPrior open(Path file)
    {
        Arena arena = Arena.ofShared();
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ))
        {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            return new LexicalPrior(arena, seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN));
        }
        catch (IOException e)
        {
            arena.close();
            throw new UncheckedIOException("cannot open " + file, e);
        }
        catch (RuntimeException e)
        {
            arena.close();
            throw e;
        }
    }

    // Mở file nếu có, không có thì trả về bản rỗng thay vì ném lỗi.
    public static LexicalPrior openIfPresent(Path file)
    {
        return Files.isRegularFile(file) ? open(file) : empty();
    }

    public static LexicalPrior empty()
    {
        return new LexicalPrior(null, null);
    }

    public boolean isAvailable()
    {
        return buf != null && enCount > 0;
    }

    public int wordCount()
    {
        return enCount;
    }

    // Xác suất từ tiếng Anh en được dịch thành âm tiết viToken.
    public double probability(String en, String viToken)
    {
        if (!isAvailable())
            return 0;
        int viId = viIdOf(viToken);
        if (viId < 0)
            return 0;
        int i = enIndexOf(en);
        if (i < 0)
            return 0;
        for (int[] posting : postings(i))
        {
            if (posting[0] == viId)
                return LexiconFormat.dequantize(posting[1]);
            if (posting[0] > viId)
                break; // postings sắp tăng dần
        }
        return 0;
    }

    // Chấm điểm một nghĩa: cộng xác suất các âm tiết rồi chia cho căn bậc hai số âm tiết. Chia thẳng thì nghĩa một chữ
    // ("phủ") luôn thắng nghĩa hai chữ ("chính phủ") dù nghĩa hai chữ mới đúng.
    public double scoreGloss(String en, List<String> viTokens)
    {
        if (!isAvailable() || viTokens.isEmpty())
            return 0;
        int i = enIndexOf(en);
        if (i < 0)
            return 0;
        List<int[]> postings = postings(i);
        if (postings.isEmpty())
            return 0;

        double sum = 0;
        int matched = 0;
        for (String token : viTokens)
        {
            int viId = viIdOf(token);
            if (viId < 0)
                continue;
            for (int[] p : postings)
            {
                if (p[0] == viId)
                {
                    sum += LexiconFormat.dequantize(p[1]);
                    matched++;
                    break;
                }
                if (p[0] > viId)
                    break;
            }
        }
        if (matched == 0)
            return 0;
        return sum / Math.sqrt(viTokens.size());
    }

    // Các bản dịch hay gặp nhất của một từ, tiện cho CLI và gỡ lỗi bằng mắt.
    public List<String> topTranslations(String en, int limit)
    {
        if (!isAvailable())
            return List.of();
        int i = enIndexOf(en);
        if (i < 0)
            return List.of();
        List<int[]> postings = new ArrayList<>(postings(i));
        postings.sort((a, b) -> Integer.compare(b[1], a[1]));
        List<String> out = new ArrayList<>(Math.min(limit, postings.size()));
        for (int k = 0; k < postings.size() && k < limit; k++)
        {
            out.add(viTokenOf(postings.get(k)[0])
                    + String.format(java.util.Locale.ROOT, "(%.3f)", LexiconFormat.dequantize(postings.get(k)[1])));
        }
        return out;
    }

    private List<int[]> postings(int enIndex)
    {
        int base = enPtrBase + enIndex * LexiconFormat.EN_PTR_SIZE;
        int n = buf.getInt(base + 4);
        long offset = buf.getLong(base + 8);
        ByteBuffer dup = buf.duplicate();
        dup.position((int) offset);
        List<int[]> out = new ArrayList<>(n);
        int id = 0;
        for (int k = 0; k < n; k++)
        {
            id += VarInt.read(dup);
            int lo = dup.get() & 0xFF;
            int hi = dup.get() & 0xFF;
            out.add(new int[]{id, lo | (hi << 8)});
        }
        return out;
    }

    private int enIndexOf(String word)
    {
        byte[] target = word.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = enCount - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int keyOffset = buf.getInt(enPtrBase + mid * LexiconFormat.EN_PTR_SIZE);
            int cmp = Utf8Compare.compareAt(buf, enKeysBase + keyOffset, target);
            if (cmp < 0)
                lo = mid + 1;
            else
                if (cmp > 0)
                    hi = mid - 1;
                else
                    return mid;
        }
        return -1;
    }

    private int viIdOf(String token)
    {
        byte[] target = token.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = viCount - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int keyOffset = buf.getInt(viPtrBase + mid * LexiconFormat.VI_PTR_SIZE);
            int cmp = Utf8Compare.compareAt(buf, viKeysBase + keyOffset, target);
            if (cmp < 0)
                lo = mid + 1;
            else
                if (cmp > 0)
                    hi = mid - 1;
                else
                    return mid;
        }
        return -1;
    }

    private String viTokenOf(int id)
    {
        int at = viKeysBase + buf.getInt(viPtrBase + id * LexiconFormat.VI_PTR_SIZE);
        int len = 0;
        while (buf.get(at + len) != 0)
            len++;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++)
            b[i] = buf.get(at + i);
        return new String(b, StandardCharsets.UTF_8);
    }

    @Override
    public void close()
    {
        if (arena != null)
            arena.close();
    }
}
