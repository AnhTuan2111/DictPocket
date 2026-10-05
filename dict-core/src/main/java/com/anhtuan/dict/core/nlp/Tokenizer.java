package com.anhtuan.dict.core.nlp;

import java.util.ArrayList;
import java.util.List;

// Tách câu thành token, giữ offset gốc để UI tô màu/bấm đúng vị trí. Bất biến: nối text()
// của mọi token phải ra đúng chuỗi đầu vào.
public final class Tokenizer
{
    private Tokenizer()
    {
    }

    // start inclusive, end exclusive.
    public record Token(String text, int start, int end, boolean isWord)
    {
    }

    public static List<Token> tokenize(String text)
    {
        if (text == null || text.isEmpty())
            return List.of();
        List<Token> out = new ArrayList<>(text.length() / 4 + 4);
        int i = 0;
        int n = text.length();
        while (i < n)
        {
            if (isWordStart(text.charAt(i)))
            {
                int j = specialTokenEnd(text, i);
                if (j < 0)
                {
                    j = i + 1;
                    while (j < n && isWordPart(text, j))
                        j++;
                }
                out.add(new Token(text.substring(i, j), i, j, true));
                i = j;
            }
            else
            {
                int j = i + 1;
                while (j < n && !isWordStart(text.charAt(j)))
                    j++;
                out.add(new Token(text.substring(i, j), i, j, false));
                i = j;
            }
        }
        return out;
    }

    // Đường link và "e.g." / "i.e." là một token nguyên vẹn, không bị chặt ở dấu chấm và dấu gạch chéo.
    // Trả về vị trí kết thúc, hoặc -1 nếu không phải token đặc biệt.
    private static int specialTokenEnd(String s, int i)
    {
        if (s.startsWith("http://", i) || s.startsWith("https://", i) || s.startsWith("www.", i))
        {
            int j = i;
            while (j < s.length() && !Character.isWhitespace(s.charAt(j)))
                j++;
            // Dấu câu cuối không thuộc đường link: "see example.com/a." chấm là hết câu
            while (j > i + 1 && ".,;:!?)\"'”’".indexOf(s.charAt(j - 1)) >= 0)
                j--;
            return j;
        }
        for (String abbreviation : new String[]{"e.g.", "i.e."})
        {
            if (s.regionMatches(true, i, abbreviation, 0, abbreviation.length())
                    && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                    && (i + abbreviation.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(i + abbreviation.length()))))
                return i + abbreviation.length();
        }
        return -1;
    }

    private static boolean isWordStart(char c)
    {
        return Character.isLetterOrDigit(c);
    }

    // Dấu nháy và gạch ngang thuộc từ khi sau nó còn chữ: "don't", "acid-proof" là một token,
    // còn "end." thì dấu chấm bị tách.
    private static boolean isWordPart(String s, int i)
    {
        char c = s.charAt(i);
        if (Character.isLetterOrDigit(c))
            return true;
        // Số giữ nguyên dạng 3.5, 1,000, 10:30, 12/05: dấu phân cách kẹp giữa hai chữ số thì thuộc về số
        if ((c == '.' || c == ',' || c == ':' || c == '/') && i > 0 && Character.isDigit(s.charAt(i - 1))
                && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))
            return true;
        if (c != '\'' && c != '-' && c != '’')
            return false;
        return i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i + 1));
    }
}
