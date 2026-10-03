package com.anhtuan.dict.core.index;

import java.util.ArrayList;
import java.util.List;

// Fuzzy chống gõ sai bằng trigram ký tự: "word" -> $$w, $wo, wor, ord, rd$, d$$ (hai ký tự '$' đệm ở hai đầu
// giúp phân biệt đầu/cuối từ). Xếp hạng bằng hệ số Jaccard; chỉ kích hoạt khi tra chính xác đã trượt
// vì chạy song song vừa chậm vừa sinh nhiều kết quả rác.
public final class TrigramIndex
{
    private TrigramIndex()
    {
    }

    private static final char PAD = '$';

    public static List<String> trigrams(String word)
    {
        if (word == null || word.isEmpty())
            return List.of();
        String padded = PAD + (PAD + word + PAD) + PAD;
        List<String> out = new ArrayList<>(padded.length());
        for (int i = 0; i + 3 <= padded.length(); i++)
        {
            out.add(padded.substring(i, i + 3));
        }
        return out;
    }

    // Hệ số Jaccard giữa truy vấn và một tài liệu: số trigram khớp chia cho tổng số trigram hai bên (trừ phần giao).
    // matched = số trigram truy vấn tìm thấy trong tài liệu, queryCount = tổng trigram truy vấn, docCount = tổng
    // trigram tài liệu (từ DOC_LENS).
    public static double jaccard(int matched, int queryCount, int docCount)
    {
        int union = queryCount + docCount - matched;
        return union <= 0 ? 0 : (double) matched / union;
    }
}
