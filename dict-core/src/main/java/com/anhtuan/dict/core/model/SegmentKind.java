package com.anhtuan.dict.core.model;

// Phân loại một đoạn trong kết quả dịch câu.
public enum SegmentKind
{
    // Từ đơn tra được trong từ điển.
    WORD,
    // Cụm nhiều từ khớp longest-match ("give up", "about to").
    PHRASE,
    // Dấu câu, khoảng trắng: giữ nguyên, không tra.
    PUNCT,
    // Không tra được kể cả sau khi lemmatize: giữ nguyên từ gốc.
    UNKNOWN,
    // Cả câu đã được dịch trọn vẹn bởi một NMT engine; v1 không sinh kind này, để dành cho OnnxNmtEngine ở v2.
    TRANSLATED
}
