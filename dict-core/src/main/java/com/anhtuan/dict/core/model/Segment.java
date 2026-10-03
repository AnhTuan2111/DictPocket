package com.anhtuan.dict.core.model;

import java.util.List;

// Một đoạn trong kết quả dịch câu, ánh xạ ngược về vị trí trong câu gốc (startOffset inclusive, endOffset exclusive).
// translate() trả List<Segment> chứ không trả String để UI dùng chung cho hai chế độ:
// gloss engine trả nhiều Segment (mỗi cái nhiều candidates), NMT engine trả một Segment kind=TRANSLATED.
public record Segment(String sourceText, int startOffset, int endOffset, SegmentKind kind, List<Candidate> candidates)
{
    public Segment
    {
        if (sourceText == null)
            throw new IllegalArgumentException("sourceText must not be null");
        if (startOffset < 0 || endOffset < startOffset)
        {
            throw new IllegalArgumentException("invalid offsets: " + startOffset + ".." + endOffset);
        }
        candidates = List.copyOf(candidates);
    }

    // Nghĩa hiển thị mặc định, null nếu không tra được.
    public String displayGloss()
    {
        return candidates.isEmpty() ? null : candidates.getFirst().gloss();
    }
}
