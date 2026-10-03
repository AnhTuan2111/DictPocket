package com.anhtuan.dict.core.index;

import java.nio.charset.StandardCharsets;

// Hằng số định dạng file index, dùng chung cho vi.idx (có dấu), vi-nodiac.idx (bỏ dấu), tri.idx (trigram).
// HEADER 48 byte, little-endian:
//    0  magic         8B   "VIENIDX2"
//    8  termCount     i32
//   12  docCount      i32
//   16  avgDocLen     f32
//   20  reserved      i32  = 0
//   24  termsOffset   i64
//   32  termPtrOffset i64
//   40  docLenOffset  i64
// Các vùng:
//   TERMS     term sắp theo thứ tự byte UTF-8, mỗi term kết thúc 0x00
//   TERM_PTRS termCount x 16B { termOffset:i32, docFreq:i32, postingOffset:i64 }
//   DOC_LENS  docCount x u16, độ dài tài liệu cho mẫu số BM25 (|D|); tài liệu dài nhất ~3.000 token
//   POSTINGS  mỗi term: docFreq cặp ( varint (deltaDocId << 1 | coTf), [varint tf] )
// deltaDocId là hiệu so với docId trước nên varint chỉ tốn 1-2 byte thay vì 4.
// Bit thấp nhất báo "có byte tf đằng sau"; 85% cặp có tf = 1 nên bỏ hẳn byte tf, tiết kiệm gần 1 MB.
public final class IndexFormat
{
    private IndexFormat()
    {
    }

    public static final byte[] MAGIC = "VIENIDX2".getBytes(StandardCharsets.US_ASCII);
    public static final int HEADER_SIZE = 48;
    public static final int TERM_PTR_SIZE = 16;
    public static final int MAX_DOC_LENGTH = 0xFFFF;

    public static final int OFF_TERM_COUNT = 8;
    public static final int OFF_DOC_COUNT = 12;
    public static final int OFF_AVG_DOC_LEN = 16;
    public static final int OFF_TERMS_OFFSET = 24;
    public static final int OFF_TERM_PTR_OFFSET = 32;
    public static final int OFF_DOC_LEN_OFFSET = 40;

    // Tên ba file index trong thư mục dữ liệu.
    public static final String VI_INDEX = "vi.idx";
    public static final String VI_NODIAC_INDEX = "vi-nodiac.idx";
    public static final String TRIGRAM_INDEX = "tri.idx";
}
