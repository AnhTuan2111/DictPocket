package com.anhtuan.dict.core.lexicon;

import java.nio.charset.StandardCharsets;

// Hằng số định dạng lex.bin, bảng xác suất dịch từ: từ tiếng Anh e thường dịch thành những âm tiết tiếng Việt nào, xác suất bao nhiêu.
// Ví dụ sau khi học từ 3,5 triệu cặp câu:
//   government -> chính(0,41) phủ(0,38) chính_quyền...
//   plan       -> kế(0,22) hoạch(0,21) kế_hoạch...
// Nhờ đó RuleBasedTranslationEngine chọn "kế hoạch" thay vì "sơ đồ", điều luật ngữ pháp không quyết định được.
// HEADER 56 byte, little-endian:
//    0  magic         8B   "VIENLEX1"
//    8  enCount       i32  số từ tiếng Anh trong bảng
//   12  viCount       i32  số âm tiết tiếng Việt trong từ vựng dịch
//   16  topK          i32  số bản dịch giữ lại cho mỗi từ
//   20  reserved      i32  = 0
//   24  enKeysOffset  i64
//   32  enPtrOffset   i64
//   40  viKeysOffset  i64
//   48  viPtrOffset   i64
// Các vùng:
//   EN_KEYS   từ tiếng Anh sắp theo thứ tự byte UTF-8, kết thúc 0x00
//   EN_PTRS   enCount x 16B { keyOffset:i32, n:i32, postingOffset:i64 }
//   VI_KEYS   âm tiết tiếng Việt đã sắp, kết thúc 0x00; id chính là thứ tự ở đây
//   VI_PTRS   viCount x 4B { keyOffset:i32 }
//   POSTINGS  với mỗi từ tiếng Anh: n cặp ( varint deltaViId, u16 prob )
// prob lưu dạng số nguyên 16 bit round(p * 65535); sai số 1/65535 không đáng kể so với sai số ước lượng và tiết kiệm một nửa so với float.
public final class LexiconFormat
{
    private LexiconFormat()
    {
    }

    public static final byte[] MAGIC = "VIENLEX1".getBytes(StandardCharsets.US_ASCII);
    public static final int HEADER_SIZE = 56;
    public static final int EN_PTR_SIZE = 16;
    public static final int VI_PTR_SIZE = 4;

    // Tên file trong thư mục dữ liệu.
    public static final String FILE_NAME = "lex.bin";

    // Xác suất dưới mức này thì không lưu: nhiều thống kê, giữ lại chỉ tốn chỗ.
    public static final double MIN_PROBABILITY = 0.005;

    public static final int OFF_EN_COUNT = 8;
    public static final int OFF_VI_COUNT = 12;
    public static final int OFF_TOP_K = 16;
    public static final int OFF_EN_KEYS = 24;
    public static final int OFF_EN_PTRS = 32;
    public static final int OFF_VI_KEYS = 40;
    public static final int OFF_VI_PTRS = 48;

    public static int quantize(double probability)
    {
        int q = (int) Math.round(probability * 65535.0);
        return Math.max(0, Math.min(65535, q));
    }

    public static double dequantize(int quantized)
    {
        return (quantized & 0xFFFF) / 65535.0;
    }
}
