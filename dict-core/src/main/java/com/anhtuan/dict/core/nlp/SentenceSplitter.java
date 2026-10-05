package com.anhtuan.dict.core.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

// Tách đoạn văn tiếng Anh thành từng câu. Các bộ dịch chỉ làm việc tốt với một câu một lần: mô hình
// nơ-ron bỏ rơi hoặc cắt cụt khi gặp đoạn dài, còn bộ luật thì sắp xếp lại trật tự theo cả đoạn.
public final class SentenceSplitter
{
    // Từ viết tắt đứng trước dấu chấm mà chưa phải hết câu (so khớp không phân biệt hoa thường)
    private static final Set<String> ABBREVIATIONS = Set.of("mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "e.g",
            "i.e", "no", "inc", "ltd", "co", "corp", "fig", "approx", "mt", "u.s", "u.k", "a.m", "p.m", "ph.d", "cf", "al");

    private SentenceSplitter()
    {
    }

    // Mỗi câu giữ nguyên dấu kết thúc, đã bỏ khoảng trắng hai đầu. Chuỗi rỗng cho danh sách rỗng.
    public static List<String> split(String text)
    {
        List<String> out = new ArrayList<>();
        if (text == null)
        {
            return out;
        }
        String s = text.strip();
        int start = 0;
        int n = s.length();
        for (int i = 0; i < n; i++)
        {
            char c = s.charAt(i);
            if (c == '\n')
            {
                // Dòng trống hoặc xuống dòng là ranh giới câu
                addIfNotBlank(out, s.substring(start, i));
                start = i + 1;
                continue;
            }
            if (!isTerminator(c))
            {
                continue;
            }
            int end = i;
            while (end + 1 < n && (isTerminator(s.charAt(end + 1)) || isCloser(s.charAt(end + 1))))
            {
                end++;
            }
            if (end + 1 < n && Character.isWhitespace(s.charAt(end + 1)) && endsSentence(s, start, i, end))
            {
                addIfNotBlank(out, s.substring(start, end + 1));
                start = end + 1;
            }
            i = end;
        }
        addIfNotBlank(out, s.substring(start));
        return out;
    }

    // Câu quá dài (không có dấu chấm nào) thì cắt tiếp ở dấu phẩy, chấm phẩy, hai chấm để mỗi mảnh vừa
    // sức mô hình. Mảnh nào cũng không vượt maxChars trừ khi không có chỗ cắt nào.
    public static List<String> splitLong(String sentence, int maxChars)
    {
        List<String> out = new ArrayList<>();
        String rest = sentence.strip();
        while (rest.length() > maxChars)
        {
            int cut = -1;
            for (int i = Math.min(maxChars, rest.length() - 1); i > maxChars / 2; i--)
            {
                char c = rest.charAt(i);
                if ((c == ',' || c == ';' || c == ':') && i + 1 < rest.length() && Character.isWhitespace(rest.charAt(i + 1)))
                {
                    cut = i;
                    break;
                }
            }
            if (cut < 0)
            {
                break;
            }
            out.add(rest.substring(0, cut + 1).strip());
            rest = rest.substring(cut + 1).strip();
        }
        if (!rest.isEmpty())
        {
            out.add(rest);
        }
        return out;
    }

    private static boolean endsSentence(String s, int sentenceStart, int terminatorAt, int endAt)
    {
        char t = s.charAt(terminatorAt);
        int next = endAt + 1;
        while (next < s.length() && Character.isWhitespace(s.charAt(next)))
        {
            next++;
        }
        if (next >= s.length())
        {
            return true;
        }
        char following = s.charAt(next);
        // Câu sau phải mở đầu bằng chữ hoa, chữ số hoặc ngoặc/nháy mở: "e.g. this" không phải ranh giới
        boolean opensSentence = Character.isUpperCase(following) || Character.isDigit(following) || following == '"'
                || following == '\'' || following == '(' || following == '[' || following == '“' || following == '‘';
        if (!opensSentence)
        {
            return false;
        }
        if (t != '.')
        {
            return true;
        }
        return !isAbbreviationOrInitial(s, sentenceStart, terminatorAt);
    }

    // Từ ngay trước dấu chấm: viết tắt đã biết, hoặc một chữ cái hoa đứng một mình (J. K. Rowling)
    private static boolean isAbbreviationOrInitial(String s, int sentenceStart, int dotAt)
    {
        int from = dotAt;
        while (from > sentenceStart && !Character.isWhitespace(s.charAt(from - 1)))
        {
            from--;
        }
        String word = s.substring(from, dotAt).replaceAll("^[\"'(\\[“‘]+", "");
        if (word.length() == 1 && Character.isUpperCase(word.charAt(0)))
        {
            return true;
        }
        return ABBREVIATIONS.contains(word.toLowerCase());
    }

    private static boolean isTerminator(char c)
    {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static boolean isCloser(char c)
    {
        return c == '"' || c == '\'' || c == ')' || c == ']' || c == '”' || c == '’';
    }

    private static void addIfNotBlank(List<String> out, String piece)
    {
        String trimmed = piece.strip();
        if (!trimmed.isEmpty())
        {
            out.add(trimmed);
        }
    }
}
