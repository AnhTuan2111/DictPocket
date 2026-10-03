package com.anhtuan.dict.core.nlp;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

// Khớp cụm từ longest-match. Không dùng Aho-Corasick (~80 MB heap với 24.585 cụm): KEYS của
// pack đã sắp sẵn nên binary search là đủ. HashSet starters (từ mở đầu của mọi cụm, ~48 KB)
// cho phép bỏ qua probe với ~90% từ.
public final class PhraseProbe
{
    private PhraseProbe()
    {
    }

    // Đo được: 99% cụm có từ 4 từ trở xuống.
    public static final int MAX_PHRASE_WORDS = 4;

    // key là khoá thật sự tìm thấy trong từ điển (có thể đã lemma hoá từ đầu).
    public record Match(int wordCount, String key)
    {
    }

    // Cụm động từ không bao giờ kết thúc bằng đại từ tân ngữ/sở hữu: "gave me a book" không
    // được cắt thành "gave me" + "a book" (nguồn có mục "!give me").
    private static final Set<String> OBJECT_PRONOUNS = Set.of("me", "you", "him", "her", "us", "them", "it", "my",
            "your", "his", "its", "our", "their");

    // Tìm cụm dài nhất bắt đầu tại from, thử 4, 3, 2 từ và dừng ngay khi khớp.
    // Thử cả dạng lemma của từ đầu: "gave up" phải khớp "give up".
    // words đã chuẩn hoá; starters xem PackReader#multiWordStarters; inDict là PackReader::contains.
    // Trả về null nếu không có cụm nào.
    public static Match longestMatch(List<String> words, int from, Set<String> starters, Predicate<String> inDict)
    {
        String first = words.get(from);
        List<String> firstForms = new ArrayList<>(4);
        if (starters.contains(first))
            firstForms.add(first);
        for (String lemma : Lemmatizer.candidates(first))
        {
            if (starters.contains(lemma))
                firstForms.add(lemma);
        }
        if (firstForms.isEmpty())
            return null; // bỏ qua ~90% lần probe

        int maxN = Math.min(MAX_PHRASE_WORDS, words.size() - from);
        for (int n = maxN; n >= 2; n--)
        {
            if (OBJECT_PRONOUNS.contains(words.get(from + n - 1)))
                continue;
            for (String head : firstForms)
            {
                StringBuilder sb = new StringBuilder(32).append(head);
                for (int k = 1; k < n; k++)
                    sb.append(' ').append(words.get(from + k));
                String key = sb.toString();
                if (inDict.test(key))
                    return new Match(n, key);
            }
        }
        return null;
    }
}
