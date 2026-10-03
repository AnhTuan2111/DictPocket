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
                int j = i + 1;
                while (j < n && isWordPart(text, j))
                    j++;
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
        if (c != '\'' && c != '-' && c != '’')
            return false;
        return i + 1 < s.length() && Character.isLetterOrDigit(s.charAt(i + 1));
    }
}
