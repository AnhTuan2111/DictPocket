package com.anhtuan.dict.core.pack;

import java.nio.charset.StandardCharsets;

// Hằng số định dạng dict.pack. HEADER 72 byte, little-endian toàn bộ:
//    0  magic          8B    "VIENDICT" (ASCII)
//    8  formatVersion  i32   = 1
//   12  flags          i32   bit0 = compressed
//   16  entryCount     i32   số entry thực nằm trong BLOCKS
//   20  blockCount     i32
//   24  keyCount       i32   số khóa trong KEYS / ENTRY_PTRS (>= entryCount)
//   28  reserved       i32   = 0
//   32  keysOffset     i64
//   40  keysLength     i64
//   48  entryPtrOffset i64
//   56  blockDirOffset i64
//   64  dataOffset     i64
// keyCount lớn hơn entryCount vì KEYS còn chứa khóa bí danh của cụm thành ngữ,
// ví dụ "give up" trỏ về entry "@give" (~121.000 khóa so với 108.854 entry).
public final class PackFormat
{
    private PackFormat()
    {
    }

    public static final byte[] MAGIC = "VIENDICT".getBytes(StandardCharsets.US_ASCII);
    public static final int FORMAT_VERSION = 1;
    public static final int HEADER_SIZE = 72;

    // Số entry mỗi block: nhỏ thì giải nén nhanh, lớn thì nén tốt hơn; 64 là điểm cân bằng đã đo.
    public static final int ENTRIES_PER_BLOCK = 256;

    public static final int ENTRY_PTR_SIZE = 12; // keyOffset:i32 + blockId:i32 + indexInBlock:i32
    public static final int BLOCK_DIR_SIZE = 16; // fileOffset:i64 + compressedLen:i32 + rawLen:i32

    public static final int FLAG_COMPRESSED = 1;

    // Số block giữ trong LRU cache: 64 x ~9 KB = ~600 KB.
    public static final int BLOCK_CACHE_SIZE = 64;

    // Vị trí các trường trong HEADER, dùng chung cho writer và reader.
    public static final int OFF_FORMAT_VERSION = 8;
    public static final int OFF_FLAGS = 12;
    public static final int OFF_ENTRY_COUNT = 16;
    public static final int OFF_BLOCK_COUNT = 20;
    public static final int OFF_KEY_COUNT = 24;
    public static final int OFF_KEYS_OFFSET = 32;
    public static final int OFF_KEYS_LENGTH = 40;
    public static final int OFF_ENTRY_PTR_OFFSET = 48;
    public static final int OFF_BLOCK_DIR_OFFSET = 56;
    public static final int OFF_DATA_OFFSET = 64;
}
