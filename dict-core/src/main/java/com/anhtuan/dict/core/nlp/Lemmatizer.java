package com.anhtuan.dict.core.nlp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// Đưa từ về dạng nguyên thể. Không dùng Porter stemmer vì nó ra "runn", tra từ điển trượt.
// Thay vào đó sinh các ứng viên lemma rồi để LookupService kiểm chứng với từ điển:
//   studies -> [studie, study, studi] -> chỉ "study" có trong từ điển
// Gồm hai tầng: bảng bất quy tắc nhúng trong code và luật hậu tố có từ điển làm trọng tài.
public final class Lemmatizer
{
    private Lemmatizer()
    {
    }

    // Bất quy tắc (động từ, danh từ số nhiều, tính từ so sánh): luật hậu tố không đoán được
    // went, mice, better nên bắt buộc tra bảng.
    private static final Map<String, String> IRREGULAR = Map.ofEntries(
            // Động từ: quá khứ và quá khứ phân từ.
            Map.entry("was", "be"), Map.entry("were", "be"), Map.entry("been", "be"), Map.entry("am", "be"),
            Map.entry("is", "be"), Map.entry("are", "be"), Map.entry("had", "have"), Map.entry("has", "have"),
            Map.entry("did", "do"), Map.entry("does", "do"), Map.entry("done", "do"), Map.entry("went", "go"),
            Map.entry("gone", "go"), Map.entry("goes", "go"), Map.entry("made", "make"), Map.entry("said", "say"),
            Map.entry("took", "take"), Map.entry("taken", "take"), Map.entry("came", "come"), Map.entry("gave", "give"),
            Map.entry("given", "give"), Map.entry("got", "get"), Map.entry("gotten", "get"), Map.entry("saw", "see"),
            Map.entry("seen", "see"), Map.entry("knew", "know"), Map.entry("known", "know"),
            Map.entry("thought", "think"), Map.entry("brought", "bring"), Map.entry("bought", "buy"),
            Map.entry("caught", "catch"), Map.entry("taught", "teach"), Map.entry("fought", "fight"),
            Map.entry("sought", "seek"), Map.entry("found", "find"), Map.entry("felt", "feel"),
            Map.entry("kept", "keep"), Map.entry("left", "leave"), Map.entry("lost", "lose"),
            Map.entry("meant", "mean"), Map.entry("met", "meet"), Map.entry("paid", "pay"), Map.entry("put", "put"),
            Map.entry("read", "read"), Map.entry("ran", "run"), Map.entry("run", "run"), Map.entry("sat", "sit"),
            Map.entry("sent", "send"), Map.entry("slept", "sleep"), Map.entry("sold", "sell"),
            Map.entry("spent", "spend"), Map.entry("stood", "stand"), Map.entry("told", "tell"),
            Map.entry("understood", "understand"), Map.entry("won", "win"), Map.entry("wrote", "write"),
            Map.entry("written", "write"), Map.entry("spoke", "speak"), Map.entry("spoken", "speak"),
            Map.entry("broke", "break"), Map.entry("broken", "break"), Map.entry("chose", "choose"),
            Map.entry("chosen", "choose"), Map.entry("drove", "drive"), Map.entry("driven", "drive"),
            Map.entry("ate", "eat"), Map.entry("eaten", "eat"), Map.entry("fell", "fall"), Map.entry("fallen", "fall"),
            Map.entry("flew", "fly"), Map.entry("flown", "fly"), Map.entry("forgot", "forget"),
            Map.entry("forgotten", "forget"), Map.entry("grew", "grow"), Map.entry("grown", "grow"),
            Map.entry("held", "hold"), Map.entry("heard", "hear"), Map.entry("hid", "hide"),
            Map.entry("hidden", "hide"), Map.entry("hit", "hit"), Map.entry("hurt", "hurt"), Map.entry("led", "lead"),
            Map.entry("lay", "lie"), Map.entry("lain", "lie"), Map.entry("rode", "ride"), Map.entry("ridden", "ride"),
            Map.entry("rose", "rise"), Map.entry("risen", "rise"), Map.entry("sang", "sing"), Map.entry("sung", "sing"),
            Map.entry("shook", "shake"), Map.entry("shaken", "shake"), Map.entry("shot", "shoot"),
            Map.entry("shown", "show"), Map.entry("shut", "shut"), Map.entry("sank", "sink"), Map.entry("sunk", "sink"),
            Map.entry("stole", "steal"), Map.entry("stolen", "steal"), Map.entry("struck", "strike"),
            Map.entry("swam", "swim"), Map.entry("swum", "swim"), Map.entry("threw", "throw"),
            Map.entry("thrown", "throw"), Map.entry("woke", "wake"), Map.entry("woken", "wake"),
            Map.entry("wore", "wear"), Map.entry("worn", "wear"), Map.entry("built", "build"),
            Map.entry("burnt", "burn"), Map.entry("dealt", "deal"), Map.entry("dug", "dig"),
            Map.entry("drank", "drink"), Map.entry("drunk", "drink"), Map.entry("fed", "feed"),
            Map.entry("fled", "flee"), Map.entry("froze", "freeze"), Map.entry("frozen", "freeze"),
            Map.entry("hung", "hang"), Map.entry("knelt", "kneel"), Map.entry("lent", "lend"),
            Map.entry("lit", "light"), Map.entry("rang", "ring"), Map.entry("rung", "ring"),
            Map.entry("shone", "shine"), Map.entry("slid", "slide"), Map.entry("spread", "spread"),
            Map.entry("stuck", "stick"), Map.entry("swept", "sweep"), Map.entry("swore", "swear"),
            Map.entry("sworn", "swear"), Map.entry("wept", "weep"), Map.entry("wound", "wind"),

            // Danh từ số nhiều bất quy tắc.
            Map.entry("men", "man"), Map.entry("women", "woman"), Map.entry("children", "child"),
            Map.entry("people", "person"), Map.entry("mice", "mouse"), Map.entry("geese", "goose"),
            Map.entry("feet", "foot"), Map.entry("teeth", "tooth"), Map.entry("lice", "louse"), Map.entry("oxen", "ox"),
            Map.entry("knives", "knife"), Map.entry("wives", "wife"), Map.entry("lives", "life"),
            Map.entry("leaves", "leaf"), Map.entry("halves", "half"), Map.entry("wolves", "wolf"),
            Map.entry("shelves", "shelf"), Map.entry("thieves", "thief"), Map.entry("loaves", "loaf"),
            Map.entry("selves", "self"), Map.entry("data", "datum"), Map.entry("media", "medium"),
            Map.entry("criteria", "criterion"), Map.entry("phenomena", "phenomenon"), Map.entry("analyses", "analysis"),
            Map.entry("bases", "basis"), Map.entry("crises", "crisis"), Map.entry("theses", "thesis"),
            Map.entry("indices", "index"), Map.entry("matrices", "matrix"), Map.entry("appendices", "appendix"),
            Map.entry("vertices", "vertex"),

            // Tính từ so sánh.
            Map.entry("better", "good"), Map.entry("best", "good"), Map.entry("worse", "bad"),
            Map.entry("worst", "bad"), Map.entry("more", "much"), Map.entry("most", "much"),
            Map.entry("less", "little"), Map.entry("least", "little"), Map.entry("farther", "far"),
            Map.entry("farthest", "far"), Map.entry("further", "far"), Map.entry("furthest", "far"),
            Map.entry("elder", "old"), Map.entry("eldest", "old"));

    // Dạng quá khứ bất quy tắc, tách riêng khỏi bảng trên (không lẫn số nhiều/so sánh);
    // dùng để chèn "đã" vào câu dịch.
    private static final Set<String> PAST_FORMS = Set.of("was", "were", "been", "had", "did", "done", "went", "gone",
            "made", "said", "took", "taken", "came", "gave", "given", "got", "gotten", "saw", "seen", "knew", "known",
            "thought", "brought", "bought", "caught", "taught", "fought", "sought", "found", "felt", "kept", "left",
            "lost", "meant", "met", "paid", "ran", "sat", "sent", "slept", "sold", "spent", "stood", "told",
            "understood", "won", "wrote", "written", "spoke", "spoken", "broke", "broken", "chose", "chosen", "drove",
            "driven", "ate", "eaten", "fell", "fallen", "flew", "flown", "forgot", "forgotten", "grew", "grown", "held",
            "heard", "hid", "hidden", "led", "lain", "rode", "ridden", "rose", "risen", "sang", "sung", "shook",
            "shaken", "shot", "shown", "sank", "sunk", "stole", "stolen", "struck", "swam", "swum", "threw", "thrown",
            "woke", "woken", "wore", "worn", "built", "burnt", "dealt", "dug", "drank", "drunk", "fed", "fled", "froze",
            "frozen", "hung", "knelt", "lent", "lit", "rang", "rung", "shone", "slid", "stuck", "swept", "swore",
            "sworn", "wept");

    public static boolean isPastForm(String word)
    {
        if (word == null || word.length() < 3)
            return false;
        String w = word.toLowerCase(java.util.Locale.ROOT);
        return PAST_FORMS.contains(w) || (w.endsWith("ed") && w.length() > 3);
    }

    // Trả về null nếu không có trong bảng bất quy tắc.
    public static String irregular(String word)
    {
        return IRREGULAR.get(word);
    }

    // Đoán nhanh một lemma, KHÔNG đối chiếu từ điển (cho test/log); tra cứu thật dùng candidates().
    public static String lemma(String word)
    {
        if (word == null || word.isEmpty())
            return "";
        String w = word.toLowerCase(java.util.Locale.ROOT);
        String irr = IRREGULAR.get(w);
        if (irr != null)
            return irr;
        List<String> c = candidates(w);
        return c.isEmpty() ? w : c.getFirst();
    }

    // Ứng viên lemma, sắp theo độ tin cậy giảm dần; người gọi lấy cái đầu tiên có trong từ điển.
    // Không chứa chính word (người gọi đã tra từ gốc trước).
    public static List<String> candidates(String word)
    {
        if (word == null || word.length() < 3)
            return List.of();
        String w = word.toLowerCase(java.util.Locale.ROOT);

        Set<String> out = new LinkedHashSet<>(8);
        String irr = IRREGULAR.get(w);
        if (irr != null)
            out.add(irr);

        if (w.endsWith("ies") && w.length() > 4)
        {
            out.add(w.substring(0, w.length() - 3) + "y"); // studies -> study
        }
        if (w.endsWith("ied") && w.length() > 4)
        {
            out.add(w.substring(0, w.length() - 3) + "y"); // studied -> study
        }
        if (w.endsWith("ing"))
            addStemVariants(out, w.substring(0, w.length() - 3));
        if (w.endsWith("ed"))
        {
            addStemVariants(out, w.substring(0, w.length() - 2));
            out.add(w.substring(0, w.length() - 1)); // moved -> move
        }
        if (w.endsWith("es") && w.length() > 3)
        {
            out.add(w.substring(0, w.length() - 2)); // goes -> go
            out.add(w.substring(0, w.length() - 1)); // uses -> use
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3)
        {
            out.add(w.substring(0, w.length() - 1)); // runs -> run
        }
        if (w.endsWith("ly") && w.length() > 4)
        {
            out.add(w.substring(0, w.length() - 2)); // quickly -> quick
            out.add(w.substring(0, w.length() - 2) + "e"); // simply -> simple
        }
        if (w.endsWith("est") && w.length() > 5)
            addStemVariants(out, w.substring(0, w.length() - 3));
        if (w.endsWith("er") && w.length() > 4)
            addStemVariants(out, w.substring(0, w.length() - 2));

        out.remove(w);
        return new ArrayList<>(out);
    }

    // Ba biến thể của gốc sau khi bỏ hậu tố, theo thứ tự ưu tiên: bỏ phụ âm đôi (runn -> run,
    // xếp trước vì "runn" không phải từ), thêm lại 'e' (mak -> make), và gốc nguyên vẹn.
    private static void addStemVariants(Set<String> out, String stem)
    {
        if (stem.length() < 2)
            return;
        if (isDoubledConsonant(stem))
        {
            out.add(stem.substring(0, stem.length() - 1));
        }
        out.add(stem);
        out.add(stem + "e");
    }

    // "runn", "stopp"... nhưng không tính ss/ll/ff/zz vì đó là dạng gốc hợp lệ.
    private static boolean isDoubledConsonant(String stem)
    {
        int n = stem.length();
        if (n < 3)
            return false;
        char a = stem.charAt(n - 2);
        char b = stem.charAt(n - 1);
        if (a != b || isVowel(b))
            return false;
        return b != 's' && b != 'l' && b != 'f' && b != 'z';
    }

    private static boolean isVowel(char c)
    {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'y';
    }
}
