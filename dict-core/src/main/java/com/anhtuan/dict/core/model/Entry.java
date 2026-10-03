package com.anhtuan.dict.core.model;

import java.util.List;

// Một mục từ hoàn chỉnh. headword giữ nguyên gốc để hiển thị; headwordNorm là khóa tra cứu, bắt buộc sinh bằng
// TextNormalizer#normalizeHeadword. variant là dạng viết khác trong ngoặc cùng dòng headword
// ("@acid-proof /.../ (acid-resisting) /.../" -> "acid-resisting"), có ở 7.128 dòng nguồn. ipa và variant có thể null.
// crossRefs lấy từ các dòng '+' (ví dụ "Xem FINANCIAL CAPITAL."), chủ yếu ở phần từ điển kinh tế.
// sourceId là nguồn từ điển, phục vụ bật/tắt nguồn lúc chạy.
public record Entry(String headword, String headwordNorm, String ipa, String variant, List<Sense> senses,
        List<Idiom> idioms, List<String> crossRefs, int sourceId)
{
    public Entry
    {
        if (headword == null)
            throw new IllegalArgumentException("headword must not be null");
        if (headwordNorm == null)
            throw new IllegalArgumentException("headwordNorm must not be null");
        senses = List.copyOf(senses);
        idioms = List.copyOf(idioms);
        crossRefs = List.copyOf(crossRefs);
    }

    // Số từ trong headword, quyết định số n-gram cần probe.
    public int wordCount()
    {
        if (headwordNorm.isEmpty())
            return 0;
        int n = 1;
        for (int i = 0; i < headwordNorm.length(); i++)
        {
            if (headwordNorm.charAt(i) == ' ')
                n++;
        }
        return n;
    }

    public boolean isMultiWord()
    {
        return wordCount() > 1;
    }
}
