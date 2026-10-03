package com.anhtuan.dict.core.pack;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.nlp.TextNormalizer;

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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Ghi dict.pack: sắp xếp entry theo headwordNorm bằng Utf8Compare (không dùng String::compareTo),
// sinh khóa bí danh cho cụm thành ngữ, chia block 64 entry rồi nén từng block bằng Deflate.
// Mọi offset được tính trước nên file ghi một lượt từ đầu đến cuối, không cần seek lại.
public final class PackWriter
{

    private PackWriter()
    {
    }

    // Một khóa trong KEYS, trỏ về entry thứ ordinal (sau khi sắp xếp).
    private record KeyPtr(String key, byte[] keyBytes, int ordinal)
    {
    }

    public static Stats write(Path target, List<Entry> entries)
    {
        List<Entry> sorted = new ArrayList<>(entries);
        // Sort ổn định: hai entry cùng headwordNorm (đồng âm) giữ nguyên thứ tự trong file nguồn.
        sorted.sort(Comparator.comparing(Entry::headwordNorm, Utf8Compare.COMPARATOR));

        List<KeyPtr> keys = collectKeys(sorted);
        keys.sort((a, b) -> Utf8Compare.compare(a.keyBytes(), b.keyBytes()));

        // KEYS: các khóa nối tiếp nhau, mỗi khóa kết thúc bằng 0x00.
        int keysLength = 0;
        for (KeyPtr k : keys)
            keysLength += k.keyBytes().length + 1;
        byte[] keysRegion = new byte[keysLength];
        int[] keyOffsets = new int[keys.size()];
        int pos = 0;
        for (int i = 0; i < keys.size(); i++)
        {
            byte[] kb = keys.get(i).keyBytes();
            keyOffsets[i] = pos;
            System.arraycopy(kb, 0, keysRegion, pos, kb.length);
            pos += kb.length + 1; // byte kết thúc 0x00: mảng đã khởi tạo sẵn bằng 0
        }

        // BLOCKS: nén từng nhóm 64 entry.
        int entryCount = sorted.size();
        int blockCount = (entryCount + PackFormat.ENTRIES_PER_BLOCK - 1) / PackFormat.ENTRIES_PER_BLOCK;
        List<byte[]> compressedBlocks = new ArrayList<>(blockCount);
        int[] rawLengths = new int[blockCount];
        long rawTotal = 0;
        for (int b = 0; b < blockCount; b++)
        {
            int from = b * PackFormat.ENTRIES_PER_BLOCK;
            int to = Math.min(from + PackFormat.ENTRIES_PER_BLOCK, entryCount);
            ByteArrayOutputStream raw = new ByteArrayOutputStream(16 * 1024);
            for (int i = from; i < to; i++)
                EntryCodec.writeEntry(raw, sorted.get(i));
            byte[] rawBytes = raw.toByteArray();
            rawLengths[b] = rawBytes.length;
            rawTotal += rawBytes.length;
            compressedBlocks.add(BlockCodec.compress(rawBytes));
        }

        // Offset tính trước hết nên không cần seek.
        long keysOffset = PackFormat.HEADER_SIZE;
        long entryPtrOffset = keysOffset + keysLength;
        long blockDirOffset = entryPtrOffset + (long) keys.size() * PackFormat.ENTRY_PTR_SIZE;
        long dataOffset = blockDirOffset + (long) blockCount * PackFormat.BLOCK_DIR_SIZE;

        try
        {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null)
                Files.createDirectories(parent);
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(target), 1 << 16))
            {
                out.write(header(entryCount, blockCount, keys.size(), keysOffset, keysLength, entryPtrOffset,
                        blockDirOffset, dataOffset));
                out.write(keysRegion);

                ByteBuffer ptrs = le(keys.size() * PackFormat.ENTRY_PTR_SIZE);
                for (int i = 0; i < keys.size(); i++)
                {
                    int ordinal = keys.get(i).ordinal();
                    ptrs.putInt(keyOffsets[i]);
                    ptrs.putInt(ordinal / PackFormat.ENTRIES_PER_BLOCK);
                    ptrs.putInt(ordinal % PackFormat.ENTRIES_PER_BLOCK);
                }
                out.write(ptrs.array());

                ByteBuffer dir = le(blockCount * PackFormat.BLOCK_DIR_SIZE);
                long fileOffset = dataOffset;
                for (int b = 0; b < blockCount; b++)
                {
                    dir.putLong(fileOffset);
                    dir.putInt(compressedBlocks.get(b).length);
                    dir.putInt(rawLengths[b]);
                    fileOffset += compressedBlocks.get(b).length;
                }
                out.write(dir.array());

                for (byte[] block : compressedBlocks)
                    out.write(block);
            }
            return new Stats(entryCount, keys.size(), blockCount, Files.size(target), rawTotal);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot write " + target, e);
        }
    }

    // Sinh toàn bộ khóa tra cứu: headword và khóa bí danh của thành ngữ.
    // Trong nguồn anhviet109K, "give up" không phải headword mà chỉ có dạng "!to give up" bên trong entry
    // "@give"; nếu chỉ đưa headword vào KEYS thì PhraseProbe trượt 7.944 cụm. Vì vậy mỗi cụm thành ngữ được
    // thêm thành khóa bí danh trỏ về entry cha, kèm biến thể bỏ tiền tố "to ".
    // Headword thật luôn thắng khóa bí danh; giữa các bí danh trùng nhau, entry đứng trước theo alphabet thắng.
    private static List<KeyPtr> collectKeys(List<Entry> sorted)
    {
        Set<String> primary = new HashSet<>(sorted.size() * 2);
        for (Entry e : sorted)
            primary.add(e.headwordNorm());

        List<KeyPtr> keys = new ArrayList<>(sorted.size() * 2);
        for (int i = 0; i < sorted.size(); i++)
        {
            String k = sorted.get(i).headwordNorm();
            if (!k.isEmpty())
                keys.add(newKey(k, i));
        }

        // Hai lượt, thứ tự quan trọng: cụm "to give up" xuất hiện ở cả "@gave" và "@give" (nguồn lặp thành ngữ
        // ở dạng quá khứ). Quét một lượt thì "@gave" thắng vì đứng trước, và tra "give up" sẽ rơi vào mục từ quá khứ.
        // Lượt 1 chỉ nhận bí danh của entry chính là từ mở đầu cụm.
        Set<String> aliasSeen = new HashSet<>();
        for (int pass = 0; pass < 2; pass++)
        {
            for (int i = 0; i < sorted.size(); i++)
            {
                Entry e = sorted.get(i);
                for (Idiom idiom : e.idioms())
                {
                    String k = TextNormalizer.normalizeHeadword(idiom.phrase());
                    String bare = k.startsWith("to ") ? k.substring(3) : k;
                    if (pass == 0 && !firstWord(bare).equals(e.headwordNorm()))
                        continue;
                    addAlias(keys, primary, aliasSeen, k, i);
                    if (!bare.equals(k))
                        addAlias(keys, primary, aliasSeen, bare, i);
                }
            }
        }
        return keys;
    }

    private static void addAlias(List<KeyPtr> keys, Set<String> primary, Set<String> seen, String key, int ordinal)
    {
        if (key.isEmpty() || primary.contains(key) || !seen.add(key))
            return;
        keys.add(newKey(key, ordinal));
    }

    private static String firstWord(String phrase)
    {
        int sp = phrase.indexOf(' ');
        return sp < 0 ? phrase : phrase.substring(0, sp);
    }

    private static KeyPtr newKey(String key, int ordinal)
    {
        return new KeyPtr(key, key.getBytes(StandardCharsets.UTF_8), ordinal);
    }

    private static byte[] header(int entryCount, int blockCount, int keyCount, long keysOffset, long keysLength,
            long entryPtrOffset, long blockDirOffset, long dataOffset)
    {
        ByteBuffer h = le(PackFormat.HEADER_SIZE);
        h.put(PackFormat.MAGIC);
        h.putInt(PackFormat.OFF_FORMAT_VERSION, PackFormat.FORMAT_VERSION);
        h.putInt(PackFormat.OFF_FLAGS, PackFormat.FLAG_COMPRESSED);
        h.putInt(PackFormat.OFF_ENTRY_COUNT, entryCount);
        h.putInt(PackFormat.OFF_BLOCK_COUNT, blockCount);
        h.putInt(PackFormat.OFF_KEY_COUNT, keyCount);
        h.putLong(PackFormat.OFF_KEYS_OFFSET, keysOffset);
        h.putLong(PackFormat.OFF_KEYS_LENGTH, keysLength);
        h.putLong(PackFormat.OFF_ENTRY_PTR_OFFSET, entryPtrOffset);
        h.putLong(PackFormat.OFF_BLOCK_DIR_OFFSET, blockDirOffset);
        h.putLong(PackFormat.OFF_DATA_OFFSET, dataOffset);
        return h.array();
    }

    private static ByteBuffer le(int size)
    {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    // Số liệu để in ra CLI.
    public record Stats(int entryCount, int keyCount, int blockCount, long fileSize, long rawSize)
    {
        public double compressionRatio()
        {
            return rawSize == 0 ? 0 : (double) fileSize / rawSize;
        }
    }
}
