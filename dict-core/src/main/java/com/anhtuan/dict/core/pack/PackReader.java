package com.anhtuan.dict.core.pack;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.nlp.TextNormalizer;

import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Đọc dict.pack bằng mmap, tra cứu bằng binary search trực tiếp trên byte (không tạo String).
// Dùng Arena thay vì FileChannel.map: MappedByteBuffer chỉ được giải phóng khi GC dọn, trên Windows
// điều đó khóa file nên build lại dict.pack trong test sẽ thất bại; Arena unmap ngay ở close().
// Block đã giải nén được cache dạng byte[] thô (~600 KB), không cache Entry[] vì tốn vài MB heap.
// Thread-safe: mỗi lần đọc dùng absolute get hoặc duplicate() riêng, cache được bảo vệ bằng synchronized.
public final class PackReader implements Closeable
{

    private final Arena arena;
    private final ByteBuffer buf;

    private final int entryCount;
    private final int keyCount;
    private final int blockCount;
    private final int keysBase;
    private final int entryPtrBase;
    private final int blockDirBase;

    // LRU block đã giải nén, có lock riêng.
    private final Map<Integer, byte[]> blockCache;

    private PackReader(Arena arena, ByteBuffer buf)
    {
        this.arena = arena;
        this.buf = buf;

        byte[] magic = new byte[PackFormat.MAGIC.length];
        for (int i = 0; i < magic.length; i++)
            magic[i] = buf.get(i);
        if (!Arrays.equals(magic, PackFormat.MAGIC))
        {
            throw new IllegalStateException("not a dict.pack file (bad magic)");
        }
        int version = buf.getInt(PackFormat.OFF_FORMAT_VERSION);
        if (version != PackFormat.FORMAT_VERSION)
        {
            throw new IllegalStateException(
                    "formatVersion " + version + " is not readable, need " + PackFormat.FORMAT_VERSION);
        }
        this.entryCount = buf.getInt(PackFormat.OFF_ENTRY_COUNT);
        this.blockCount = buf.getInt(PackFormat.OFF_BLOCK_COUNT);
        this.keyCount = buf.getInt(PackFormat.OFF_KEY_COUNT);
        this.keysBase = (int) buf.getLong(PackFormat.OFF_KEYS_OFFSET);
        this.entryPtrBase = (int) buf.getLong(PackFormat.OFF_ENTRY_PTR_OFFSET);
        this.blockDirBase = (int) buf.getLong(PackFormat.OFF_BLOCK_DIR_OFFSET);

        this.blockCache = Collections
                .synchronizedMap(new LinkedHashMap<>(PackFormat.BLOCK_CACHE_SIZE * 2, 0.75f, true)
                {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> eldest)
                    {
                        return size() > PackFormat.BLOCK_CACHE_SIZE;
                    }
                });
    }

    public static PackReader open(Path packFile)
    {
        Arena arena = Arena.ofShared();
        try (FileChannel ch = FileChannel.open(packFile, StandardOpenOption.READ))
        {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            ByteBuffer buf = seg.asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
            return new PackReader(arena, buf);
        }
        catch (IOException e)
        {
            arena.close();
            throw new UncheckedIOException("cannot open " + packFile, e);
        }
        catch (RuntimeException e)
        {
            arena.close();
            throw e;
        }
    }

    // Tra cứu chính xác. Chuỗi đầu vào đã được chuẩn hóa sẵn.
    public Optional<Entry> lookup(String headword)
    {
        String key = TextNormalizer.normalizeHeadword(headword);
        int i = indexOfKey(key);
        if (i < 0)
            return Optional.empty();
        // Khóa có thể trùng (đồng âm, hoặc nhiều nguồn cùng có từ này). Binary search rơi vào bất kỳ
        // phần tử nào trong nhóm nên phải lùi về đầu nhóm để kết quả ổn định và luôn là mục từ chính.
        while (i > 0 && keyAt(i - 1).equals(key))
            i--;
        return Optional.of(entryOfKey(i));
    }

    // Trả về tất cả entry đồng âm, ví dụ "bank" (bờ sông) và "bank" (ngân hàng) là hai entry riêng.
    public List<Entry> lookupAll(String headword)
    {
        String key = TextNormalizer.normalizeHeadword(headword);
        int i = indexOfKey(key);
        if (i < 0)
            return List.of();
        int lo = i;
        while (lo > 0 && keyAt(lo - 1).equals(key))
            lo--;
        int hi = i;
        while (hi + 1 < keyCount && keyAt(hi + 1).equals(key))
            hi++;
        List<Entry> out = new ArrayList<>(hi - lo + 1);
        for (int k = lo; k <= hi; k++)
            out.add(entryOfKey(k));
        return out;
    }

    // Kiểm tra tồn tại mà không giải nén block (đường nóng của PhraseProbe). Khóa phải đã chuẩn hóa.
    public boolean contains(String headwordNorm)
    {
        return indexOfKey(headwordNorm) >= 0;
    }

    // Các khóa bắt đầu bằng tiền tố, dùng cho gợi ý khi đang gõ.
    public List<String> prefixScan(String prefix, int limit)
    {
        String p = TextNormalizer.normalizeHeadword(prefix);
        if (p.isEmpty() || limit <= 0)
            return List.of();
        int i = indexOfKey(p);
        int start = i >= 0 ? i : -(i + 1);
        List<String> out = new ArrayList<>(Math.min(limit, 32));
        for (int k = start; k < keyCount && out.size() < limit; k++)
        {
            String key = keyAt(k);
            if (!key.startsWith(p))
                break;
            out.add(key);
        }
        return out;
    }

    // Entry thứ ordinal theo thứ tự đã sắp xếp, dùng làm docId của index.
    public Entry entryAt(int ordinal)
    {
        if (ordinal < 0 || ordinal >= entryCount)
        {
            throw new IndexOutOfBoundsException("ordinal " + ordinal + " / " + entryCount);
        }
        return decode(ordinal / PackFormat.ENTRIES_PER_BLOCK, ordinal % PackFormat.ENTRIES_PER_BLOCK);
    }

    // Các từ mở đầu của mỗi khóa nhiều từ (tập lọc cho PhraseProbe).
    // Quét vùng KEYS một lần (~1,1 MB, vài chục ms); từ nào không có trong tập này thì không thể mở đầu
    // một cụm, bỏ qua probe, cắt được khoảng 90% số lần binary search.
    public Set<String> multiWordStarters()
    {
        Set<String> starters = new HashSet<>(8192);
        for (int i = 0; i < keyCount; i++)
        {
            String key = keyAt(i);
            int sp = key.indexOf(' ');
            if (sp > 0)
                starters.add(key.substring(0, sp));
        }
        return starters;
    }

    public int entryCount()
    {
        return entryCount;
    }

    public int keyCount()
    {
        return keyCount;
    }

    public int blockCount()
    {
        return blockCount;
    }

    // Binary search trên ENTRY_PTRS. Trả về chỉ số, hoặc -(điểm chèn)-1 như Arrays#binarySearch.
    private int indexOfKey(String normalizedKey)
    {
        if (normalizedKey == null || normalizedKey.isEmpty())
            return -1;
        byte[] target = normalizedKey.getBytes(StandardCharsets.UTF_8);
        int lo = 0, hi = keyCount - 1;
        while (lo <= hi)
        {
            int mid = (lo + hi) >>> 1;
            int cmp = Utf8Compare.compareAt(buf, keysBase + keyOffsetOf(mid), target);
            if (cmp < 0)
                lo = mid + 1;
            else
                if (cmp > 0)
                    hi = mid - 1;
                else
                    return mid;
        }
        return -(lo + 1);
    }

    private int keyOffsetOf(int keyIndex)
    {
        return buf.getInt(entryPtrBase + keyIndex * PackFormat.ENTRY_PTR_SIZE);
    }

    private String keyAt(int keyIndex)
    {
        int at = keysBase + keyOffsetOf(keyIndex);
        int len = 0;
        while (buf.get(at + len) != 0)
            len++;
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++)
            b[i] = buf.get(at + i);
        return new String(b, StandardCharsets.UTF_8);
    }

    private Entry entryOfKey(int keyIndex)
    {
        int base = entryPtrBase + keyIndex * PackFormat.ENTRY_PTR_SIZE;
        return decode(buf.getInt(base + 4), buf.getInt(base + 8));
    }

    private Entry decode(int blockId, int indexInBlock)
    {
        byte[] raw = block(blockId);
        ByteBuffer bb = ByteBuffer.wrap(raw);
        for (int i = 0; i < indexInBlock; i++)
            EntryCodec.skipEntry(bb);
        return EntryCodec.readEntry(bb);
    }

    private byte[] block(int blockId)
    {
        byte[] cached = blockCache.get(blockId);
        if (cached != null)
            return cached;

        int dir = blockDirBase + blockId * PackFormat.BLOCK_DIR_SIZE;
        long fileOffset = buf.getLong(dir);
        int compressedLen = buf.getInt(dir + 8);
        int rawLen = buf.getInt(dir + 12);

        byte[] compressed = new byte[compressedLen];
        ByteBuffer dup = buf.duplicate(); // riêng cho lượt đọc này, thread-safe
        dup.position((int) fileOffset);
        dup.get(compressed);

        byte[] raw = BlockCodec.decompress(compressed, rawLen);
        blockCache.put(blockId, raw);
        return raw;
    }

    @Override
    public void close()
    {
        blockCache.clear();
        arena.close(); // unmap ngay, không chờ GC
    }
}
