package com.anhtuan.dict.core.nlp;

import java.text.Normalizer;
import java.util.Locale;

// Chuẩn hoá văn bản, dùng chung cho BUILD index và TRA CỨU: hai bên lệch nhau là binary
// search trượt.
public final class TextNormalizer
{
    private TextNormalizer()
    {
    }

    private static final char BOM = '\uFEFF';

    // Sinh headwordNorm, khoá tra cứu chính. Thứ tự các bước là bắt buộc.
    public static String normalizeHeadword(String raw)
    {
        if (raw == null)
            return "";
        String s = stripBom(raw).trim();
        s = s.replace('_', ' '); // a_la_carte -> a la carte
        s = collapseSpaces(s);
        s = s.toLowerCase(Locale.ROOT); // ROOT tránh bẫy Locale tiếng Thổ Nhĩ Kỳ (I -> ı)
        return Normalizer.normalize(s, Normalizer.Form.NFC);
    }

    // Bỏ dấu tiếng Việt cho search không dấu.
    public static String removeDiacritics(String s)
    {
        if (s == null)
            return "";
        // đ/Đ không phải chữ + dấu phụ trong Unicode nên NFD không tách được, phải thay tay trước.
        String t = s.replace('\u0111', 'd').replace('\u0110', 'D');
        t = Normalizer.normalize(t, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++)
        {
            char c = t.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK)
                sb.append(c);
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFC);
    }

    public static String normalizeVietnamese(String raw)
    {
        return removeDiacritics(stripBom(raw).trim().toLowerCase(Locale.ROOT));
    }

    // Tách nghĩa tiếng Việt thành token cho index: chuỗi liên tiếp chữ/số theo Unicode, đã
    // lowercase. Phải dùng chung ở CẢ IndexWriter và truy vấn, lệch một quy tắc là "tìm không ra".
    public static java.util.List<String> splitTokens(String text)
    {
        if (text == null || text.isEmpty())
            return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>(8);
        StringBuilder cur = new StringBuilder(16);
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c))
            {
                cur.append(Character.toLowerCase(c));
            }
            else if (cur.length() > 0)
            {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0)
            out.add(cur.toString());
        return out;
    }

    // Tách một nghĩa thành các phương án dịch riêng: "cho, biếu, tặng" -> [cho, biếu, tặng].
    // Mỗi phương án là một đơn vị độc lập (chấm điểm, rút từ ghép, hiện nghĩa ngắn).
    public static java.util.List<String> glossAlternatives(String gloss)
    {
        if (gloss == null)
            return java.util.List.of();
        String cleaned = gloss.replaceAll("\\([^)]*\\)", " ").replaceAll("\\[[^]]*]", " ");
        java.util.List<String> out = new java.util.ArrayList<>(4);
        for (String part : cleaned.split("[,;]"))
        {
            String v = part.replaceAll("\\s+", " ").trim();
            if (!v.isEmpty())
                out.add(v);
        }
        return out;
    }

    public static String stripBom(String s)
    {
        return (s != null && !s.isEmpty() && s.charAt(0) == BOM) ? s.substring(1) : s;
    }

    public static String collapseSpaces(String s)
    {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            boolean isSpace = Character.isWhitespace(c);
            if (isSpace)
            {
                if (!prevSpace && sb.length() > 0)
                    sb.append(' ');
            }
            else
            {
                sb.append(c);
            }
            prevSpace = isSpace;
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == ' ')
            end--;
        return sb.substring(0, end);
    }
}
