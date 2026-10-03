package com.anhtuan.dict.core;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Example;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;

import java.util.List;

// Từ điển mini dùng chung cho test pack, index và dịch câu, cố ý chép đúng những cái bẫy có thật của nguồn anhviet109K:
// "give up" không phải mục từ mà chỉ có ở dòng "!to give up" trong "@give"; "@bank" có hai mục trùng tên
// (bờ sông và ngân hàng); "@went" chỉ có tham chiếu chéo, không có nghĩa nào.
public final class TestEntries
{

    private TestEntries()
    {
    }

    public static Entry entry(String headword, String ipa, List<Sense> senses, List<Idiom> idioms)
    {
        return new Entry(headword, TextNormalizer.normalizeHeadword(headword), ipa, null, senses, idioms, List.of(), 0);
    }

    public static Sense sense(String pos, String... glosses)
    {
        return new Sense(pos, List.of(glosses), List.of());
    }

    public static Idiom idiom(String phrase, String... glosses)
    {
        return new Idiom(phrase, List.of(glosses), List.of());
    }

    public static Example example(String en, String vi)
    {
        return new Example(en, vi, 0);
    }

    // Từ điển 8 mục đủ để kiểm tra mọi đường đi quan trọng.
    public static List<Entry> mini()
    {
        return List.of(
                entry("give", "giv", List.of(sense("động từ", "cho, biếu, tặng", "trao cho")),
                        List.of(idiom("to give up", "bỏ, từ bỏ", "nhượng bộ"), idiom("to give in", "chịu thua"))),
                entry("look", "luk", List.of(sense("động từ", "nhìn, ngó")),
                        List.of(idiom("to look after", "chăm sóc, trông nom"))),
                entry("run", "rʌn", List.of(sense("động từ", "chạy")), List.of()),
                entry("go", "gou", List.of(sense("động từ", "đi, đi đến")), List.of()),
                // Mục từ chỉ có tham chiếu chéo, không nghĩa: phải lemma hóa tiếp mới ra "go".
                new Entry("went", "went", "went", null, List.of(), List.of(), List.of("thời quá khứ của go"), 0),
                entry("bank", "bæŋk", List.of(sense("danh từ", "bờ sông, bờ đê")), List.of()),
                entry("bank", "bæŋk", List.of(sense("danh từ", "ngân hàng")), List.of()),
                entry("a la carte", null, List.of(sense("phó từ", "gọi món theo thực đơn")), List.of()));
    }
}
