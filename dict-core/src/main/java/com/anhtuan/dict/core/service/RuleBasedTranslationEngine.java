package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.model.Candidate;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.nlp.FunctionWords;
import com.anhtuan.dict.core.nlp.Lemmatizer;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.spi.TranslationEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Dịch cả câu Anh->Việt bằng luật, dựa trên kết quả của DictionaryGlossEngine: dịch cứng từ
// chức năng (FunctionWords), chọn nghĩa theo từ loại, rồi sắp lại trật tự cho đúng tiếng Việt
// (danh ngữ ngược, thêm dấu hiệu thì). Đọc được với câu trần thuật thông thường, sai với câu
// phức/thành ngữ; muốn chất lượng hơn thì cắm NMT vào TranslationEngine.
// Trả về đúng một Segment kind TRANSLATED theo giao ước của TranslationEngine.
public final class RuleBasedTranslationEngine implements TranslationEngine
{

    public static final String ENGINE_ID = "rule-based-vi";

    private final DictionaryGlossEngine glossEngine;
    private final LookupService lookup;
    private final LexicalPrior prior;

    public RuleBasedTranslationEngine(DictionaryGlossEngine glossEngine, LookupService lookup)
    {
        this(glossEngine, lookup, LexicalPrior.empty());
    }

    // Thiếu prior thì engine vẫn chạy, chỉ chọn nghĩa kém hơn (xem pickBest).
    public RuleBasedTranslationEngine(DictionaryGlossEngine glossEngine, LookupService lookup, LexicalPrior prior)
    {
        this.glossEngine = glossEngine;
        this.lookup = lookup;
        this.prior = prior;
    }

    @Override
    public String engineId()
    {
        return ENGINE_ID;
    }

    @Override
    public String displayName()
    {
        return "Dịch câu bằng luật (thử nghiệm)";
    }

    // Từ loại đã đoán cho từng đoạn.
    private enum Pos
    {
        NOUN, VERB, ADJ, ADV, FUNC, PUNCT, UNKNOWN
    }

    private static final class Item
    {
        String source;
        String vi;
        Pos pos = Pos.UNKNOWN;
        FunctionWords.Fw fw;
        List<Candidate> candidates = List.of();
        boolean past;
        boolean gerund;
        boolean dropped;
        // Đã nuốt chữ "not" ở cạnh, từ này không được bỏ đi nữa.
        boolean negated;

        boolean isFunc(FunctionWords.Category... cats)
        {
            if (fw == null)
                return false;
            for (FunctionWords.Category c : cats)
                if (fw.cat() == c)
                    return true;
            return false;
        }

        boolean isContent()
        {
            return fw == null && pos != Pos.PUNCT;
        }
    }

    @Override
    public List<Segment> translate(String sentence)
    {
        if (sentence == null || sentence.isBlank())
            return List.of();

        List<Item> items = toItems(glossEngine.translate(sentence));
        assignPartOfSpeech(items);
        chooseVietnamese(items);
        markComparatives(items);
        applyGrammarRules(items);
        String vi = join(items);

        return List.of(new Segment(sentence, 0, sentence.length(), SegmentKind.TRANSLATED,
                List.of(new Candidate(sentence, vi, null, 1.0))));
    }

    // Bản chú giải từng cụm: UI hiện dưới câu dịch để người dùng đối chiếu và sửa.
    public List<Segment> glossSegments(String sentence)
    {
        return glossEngine.translate(sentence);
    }

    private static List<Item> toItems(List<Segment> segments)
    {
        List<Item> items = new ArrayList<>(segments.size());
        for (Segment s : segments)
        {
            String text = s.sourceText();
            if (s.kind() == SegmentKind.PUNCT)
            {
                if (text.isBlank())
                    continue; // khoảng trắng tự sinh lại lúc nối
                Item it = new Item();
                it.source = text.trim();
                it.vi = text.trim();
                it.pos = Pos.PUNCT;
                items.add(it);
                continue;
            }
            Item it = new Item();
            it.source = text;
            it.candidates = s.candidates();
            it.fw = FunctionWords.get(text);
            String firstWord = text.split("\\s+")[0];
            it.past = Lemmatizer.isPastForm(firstWord);
            it.gerund = firstWord.toLowerCase(Locale.ROOT).endsWith("ing");
            // Cụm nhiều từ: tin từ loại từ điển đã ghi. Bảng thuật ngữ tự soạn toàn danh từ cụm
            // ("design pattern"); coi là động từ thì bước sắp lại danh ngữ bỏ qua, ra "Đề xuất
            // phù hợp mẫu thiết kế" thay vì "Đề xuất mẫu thiết kế phù hợp".
            if (s.kind() == SegmentKind.PHRASE)
            {
                it.pos = hasPos(it, "động từ") ? Pos.VERB : hasPos(it, "danh từ") ? Pos.NOUN : Pos.VERB;
            }
            items.add(it);
        }
        return items;
    }

    // Đoán từ loại mà không cần POS tagger: từ điển đã ghi sẵn từ loại từng nhóm nghĩa, nên chỉ
    // cần chọn giữa vài khả năng đã liệt kê, dựa vào ngữ cảnh (mạo từ, đại từ, trợ động từ).
    private void assignPartOfSpeech(List<Item> items)
    {
        for (int i = 0; i < items.size(); i++)
        {
            Item it = items.get(i);
            if (it.pos == Pos.PUNCT)
                continue;
            if (it.fw != null)
            {
                it.pos = Pos.FUNC;
                continue;
            }
            if (it.pos == Pos.VERB)
                continue; // cụm đã chốt ở bước trước

            Item prev = i > 0 ? items.get(i - 1) : null;
            Item next = i + 1 < items.size() ? items.get(i + 1) : null;
            String w = it.source.toLowerCase(Locale.ROOT);

            boolean hasNoun = hasPos(it, "danh từ");
            boolean hasVerb = hasPos(it, "động từ");
            boolean hasAdj = hasPos(it, "tính từ");
            boolean hasAdv = hasPos(it, "phó từ") || hasPos(it, "trạng từ");

            if (w.endsWith("ly") && hasAdv)
            {
                it.pos = Pos.ADV;
            }
            else if (i == 0 && (hasVerb || promoteToVerbViaLemma(it)))
            {
                // Từ đầu câu có nghĩa động từ = câu mệnh lệnh (văn phong đề bài, hướng dẫn kỹ
                // thuật): "Map the four stages...". Thiếu luật này "Map" ra "Bản đồ".
                it.pos = Pos.VERB;
            }
            else
                if (prev != null && (prev.pos == Pos.PUNCT || prev.isFunc(FunctionWords.Category.CONJUNCTION))
                        && verbBefore(items, i) && (hasVerb || promoteToVerbViaLemma(it)))
            {
                // Liệt kê động từ: "easier to maintain, TEST, and SCALE" - vế trước dấu phẩy/"and"
                // là động từ thì vế sau cũng vậy (không thì "test" ra "vỏ", "scale" ra "sự chia độ").
                if (hasVerb)
                    preferLemmaForVerb(it);
                it.pos = Pos.VERB;
            }
                else if (it.past && afterSubject(prev) && (hasVerb || promoteToVerbViaLemma(it)))
            {
                // Dạng quá khứ ngay sau chủ ngữ là động từ chính dù từ điển có ghi thêm tính từ:
                // "The government DECIDED to...".
                if (hasVerb)
                    preferLemmaForVerb(it);
                it.pos = Pos.VERB;
            }
                else if (endsWithS(w) && subjectNounBefore(items, i) && (hasVerb || promoteToVerbViaLemma(it)))
            {
                // Chủ ngữ + từ kết thúc bằng -s = động từ ngôi thứ ba, không phải danh từ số nhiều
                // ("the system MONITORS analytics", "a patient BOOKS a slot"). Danh từ đứng
                // trước phải ở vị trí chủ ngữ (có từ dẫn đầu hoặc đầu câu), nếu không
                // "manages user ACCOUNTS" cũng bị coi là động từ.
                it.pos = Pos.VERB;
            }
                else
                    if (prev != null && prev.isFunc(FunctionWords.Category.CONJUNCTION) && hasAdj
                            && adjectiveBefore(items, i))
            {
                // Liên từ nối hai thứ cùng loại: "known and STABLE" - vế trái là tính từ thì vế
                // phải cũng vậy (không thì "stable" ra "chuồng ngựa").
                it.pos = Pos.ADJ;
            }
                    else if (prev != null && prev.isFunc(FunctionWords.Category.ADVERB) && hasAdj)
            {
                // "very COLD", "too SMALL": sau trạng từ mức độ gần như chắc chắn là tính từ.
                it.pos = Pos.ADJ;
            }
                    else if (next != null && next.isContent() && hasAdj && !(prevIsArticle(prev) && endsWithS(next.source)))
            {
                // Đứng trước một từ nội dung khác thì bổ nghĩa cho nó ("the OLD system"). Ngoại lệ:
                // mạo từ + X + từ chia -s thì X là chủ ngữ ("a patient books...").
                it.pos = Pos.ADJ;
            }
                    else
                        if (prev != null
                                && prev.isFunc(FunctionWords.Category.PRONOUN, FunctionWords.Category.MODAL,
                                        FunctionWords.Category.FUTURE, FunctionWords.Category.NEGATION, FunctionWords.Category.DO,
                                        FunctionWords.Category.INFINITIVE, FunctionWords.Category.HAVE)
                                && (hasVerb || promoteToVerbViaLemma(it)))
            {
                // Sau đại từ/trợ động từ gần như chắc chắn là động từ. Nguồn hay có mục riêng cho
                // dạng chia ("@finished" chỉ ghi tính từ) nên phải lùi về nguyên thể.
                it.pos = Pos.VERB;
            }
                        else
                            if (prev != null && prev.isFunc(FunctionWords.Category.BE) && it.gerund
                                    && (hasVerb || promoteToVerbViaLemma(it)))
            {
                it.pos = Pos.VERB;
            }
                            else
                                if (prev != null && prev.isFunc(FunctionWords.Category.ARTICLE, FunctionWords.Category.QUANTIFIER,
                                        FunctionWords.Category.POSSESSIVE, FunctionWords.Category.DEMONSTRATIVE,
                                        FunctionWords.Category.PREPOSITION) && hasNoun)
            {
                it.pos = Pos.NOUN;
            }
                                else if (it.past && (hasVerb || promoteToVerbViaLemma(it)))
            {
                if (hasVerb)
                    preferLemmaForVerb(it);
                it.pos = Pos.VERB;
            }
                                else if (hasNoun)
            {
                it.pos = Pos.NOUN;
            }
                                else if (hasVerb)
            {
                it.pos = Pos.VERB;
            }
                                else if (hasAdj)
            {
                it.pos = Pos.ADJ;
            }
                                else if (hasAdv)
            {
                it.pos = Pos.ADV;
            }
                                else
            {
                it.pos = Pos.UNKNOWN;
            }
        }
    }

    // Đổi sang nghĩa của dạng nguyên thể nếu nguyên thể cũng là động từ. Chỉ áp dụng cho động
    // từ: danh từ số nhiều ("systems") hai mục chung nghĩa, còn mục động từ chia hay mang
    // nghĩa khác hẳn.
    private void preferLemmaForVerb(Item it)
    {
        String w = it.source.toLowerCase(Locale.ROOT);
        for (String cand : Lemmatizer.candidates(w))
        {
            if (cand.equals(w))
                continue;
            var resolved = lookup.resolve(cand);
            if (resolved.isEmpty())
                continue;
            List<Candidate> viaLemma = lookup.candidatesOf(resolved.get().entries());
            for (Candidate c : viaLemma)
            {
                if (c.pos() != null && c.pos().contains("động từ"))
                {
                    it.candidates = viaLemma;
                    return;
                }
            }
        }
    }

    // "reading" trong "is reading" phải là động từ nhưng nguồn có mục @reading chỉ mang danh từ
    // ("sự đọc"): lùi về nguyên thể "read". Trả true nếu tìm được và đã thay it.candidates.
    private boolean promoteToVerbViaLemma(Item it)
    {
        for (String cand : Lemmatizer.candidates(it.source.toLowerCase(Locale.ROOT)))
        {
            var resolved = lookup.resolve(cand);
            if (resolved.isEmpty())
                continue;
            List<Candidate> viaLemma = lookup.candidatesOf(resolved.get().entries());
            for (Candidate c : viaLemma)
            {
                if (c.pos() != null && c.pos().contains("động từ"))
                {
                    it.candidates = viaLemma;
                    return true;
                }
            }
        }
        return false;
    }

    // Trước vị trí at (bỏ qua dấu câu và liên từ) có phải động từ không.
    private static boolean verbBefore(List<Item> items, int at)
    {
        for (int i = at - 1; i >= 0; i--)
        {
            Item before = items.get(i);
            if (before.dropped || before.pos == Pos.PUNCT || before.isFunc(FunctionWords.Category.CONJUNCTION))
                continue;
            return before.pos == Pos.VERB;
        }
        return false;
    }

    private static boolean prevIsArticle(Item prev)
    {
        return prev != null && prev.isFunc(FunctionWords.Category.ARTICLE);
    }

    // Ngay trước at có phải danh từ đang làm chủ ngữ không: được dẫn đầu bởi mạo từ/sở hữu/
    // chỉ định, hoặc đứng đầu câu.
    private static boolean subjectNounBefore(List<Item> items, int at)
    {
        Item prev = null;
        int prevIndex = -1;
        for (int i = at - 1; i >= 0; i--)
        {
            if (items.get(i).dropped)
                continue;
            prev = items.get(i);
            prevIndex = i;
            break;
        }
        if (prev == null || prev.pos != Pos.NOUN)
            return false;
        for (int i = prevIndex - 1; i >= 0; i--)
        {
            Item before = items.get(i);
            // Tính từ/trạng từ bổ nghĩa không phải từ dẫn đầu: ở "into smaller MODULES makes it
            // easier" từ dẫn đầu của "modules" là "into".
            if (before.dropped || before.pos == Pos.ADJ || before.pos == Pos.ADV)
                continue;
            return before.isFunc(FunctionWords.Category.ARTICLE, FunctionWords.Category.POSSESSIVE,
                    FunctionWords.Category.DEMONSTRATIVE, FunctionWords.Category.QUANTIFIER,
                    FunctionWords.Category.PREPOSITION);
        }
        return true; // danh từ mở đầu câu
    }

    // Kết thúc bằng -s nhưng không phải -ss (class, address không phải dạng chia).
    private static boolean endsWithS(String word)
    {
        return word.length() > 3 && word.endsWith("s") && !word.endsWith("ss");
    }

    // Trước liên từ ở at có phải tính từ không (cho luật "A and B thì B cùng loại với A").
    private static boolean adjectiveBefore(List<Item> items, int at)
    {
        for (int i = at - 2; i >= 0; i--)
        {
            Item before = items.get(i);
            if (before.dropped)
                continue;
            return before.pos == Pos.ADJ;
        }
        return false;
    }

    // Đứng ngay sau chủ ngữ (danh từ, đại từ) hoặc đầu câu.
    private static boolean afterSubject(Item prev)
    {
        if (prev == null)
            return true;
        return prev.pos == Pos.NOUN || prev.isFunc(FunctionWords.Category.PRONOUN);
    }

    private static boolean hasPos(Item it, String posName)
    {
        for (Candidate c : it.candidates)
        {
            if (c.pos() != null && c.pos().contains(posName))
                return true;
        }
        return false;
    }

    private void chooseVietnamese(List<Item> items)
    {
        for (Item it : items)
        {
            if (it.pos == Pos.PUNCT)
                continue;
            if (it.fw != null)
            {
                it.vi = it.fw.vi();
                continue;
            }
            it.vi = pickBest(it);
        }
    }

    // Chọn chữ tiếng Việt cho một từ, hai tầng. Tầng 1 (ngữ pháp): lọc nhóm nghĩa đúng từ loại
    // đã đoán. Tầng 2 (thống kê): trong số còn lại chọn phương án người ta hay dịch nhất theo
    // LexicalPrior (government có sáu nghĩa danh từ đúng ngữ pháp; bảng học từ 1,2 triệu cặp
    // câu cho "chính phủ" thắng "sự cai trị"). Chấm từng PHƯƠNG ÁN chứ không từng dòng nghĩa
    // vì một dòng thường là cả chùm ("cho, biếu, tặng"). Không có prior thì bỏ qua tầng 2.
    private String pickBest(Item it)
    {
        if (it.candidates.isEmpty())
            return it.source;

        String wanted = switch (it.pos)
        {
            case NOUN -> "danh từ";
            case VERB -> "động từ";
            case ADJ -> "tính từ";
            case ADV -> "phó từ";
            default -> null;
        };
        List<Candidate> pool = new ArrayList<>(it.candidates.size());
        if (wanted != null)
        {
            for (Candidate c : it.candidates)
            {
                if (c.pos() != null && c.pos().contains(wanted) && !isJunk(c.gloss()))
                    pool.add(c);
            }
        }
        if (pool.isEmpty())
        {
            for (Candidate c : it.candidates)
                if (!isJunk(c.gloss()))
                    pool.add(c);
        }
        if (pool.isEmpty())
            return shorten(it.candidates.getFirst().gloss());

        // Nguồn do người dùng xếp trên thắng tuyệt đối: bảng thuật ngữ tự soạn là quyết định có
        // ý, thống kê từ phụ đề không được đè lên (đo được: "platform" -> "nền tảng" vs "sân ga").
        int bestPriority = Integer.MAX_VALUE;
        for (Candidate c : pool)
            bestPriority = Math.min(bestPriority, lookup.priorityOf(c));
        if (bestPriority != Integer.MAX_VALUE)
        {
            List<Candidate> top = new ArrayList<>(pool.size());
            for (Candidate c : pool)
                if (lookup.priorityOf(c) == bestPriority)
                    top.add(c);
            if (!top.isEmpty())
                pool = top;
        }

        String fallback = shorten(pool.getFirst().gloss());
        if (!prior.isAvailable())
            return fallback;

        String en = it.source.toLowerCase(Locale.ROOT);
        String best = null;
        double bestScore = 0;
        for (Candidate c : pool)
        {
            for (String alt : alternatives(c.gloss()))
            {
                double score = prior.scoreGloss(en, TextNormalizer.splitTokens(alt));
                if (score == 0 && c.headword() != null)
                {
                    // Từ trong câu là dạng chia ("systems"), bảng chỉ biết dạng gốc ("system").
                    score = prior.scoreGloss(c.headword(), TextNormalizer.splitTokens(alt));
                }
                if (score > bestScore)
                {
                    bestScore = score;
                    best = alt;
                }
            }
        }
        return best != null ? best : fallback;
    }

    // Xem TextNormalizer#glossAlternatives.
    static List<String> alternatives(String gloss)
    {
        return TextNormalizer.glossAlternatives(gloss);
    }

    // Nghĩa hỏng của nguồn (sót markup, chỉ là chú thích cách dùng): nhét vào câu dịch thành rác.
    private static boolean isJunk(String gloss)
    {
        return LookupService.isJunkGloss(gloss) || shorten(gloss).isBlank();
    }

    // Một dòng nghĩa là cả chùm đồng nghĩa kèm chú thích: trong câu dịch chỉ lấy phương án đầu
    // (cần MỘT từ ở đúng chỗ). Chỉ là đường lùi khi không có prior; có thì pickBest chấm từng
    // phương án.
    static String shorten(String gloss)
    {
        if (gloss == null)
            return "";
        List<String> alts = alternatives(gloss);
        if (!alts.isEmpty())
            return alts.getFirst();
        return gloss.replaceAll("\\s+", " ").trim();
    }

    // Cấp so sánh: tiếng Việt thêm từ ("nhỏ" -> "nhỏ hơn"). Lemmatizer cắt đuôi -er/-est để tra
    // được từ điển nhưng nghĩa ra là của dạng GỐC nên mất ý so sánh ("easier" ra "dễ" thay vì
    // "dễ hơn"). Chỉ đánh dấu khi từ không có mục riêng: "user", "proper", "other" cũng đuôi -er.
    private void markComparatives(List<Item> items)
    {
        for (Item it : items)
        {
            if (it.dropped || it.vi == null || it.vi.isBlank())
                continue;
            if (it.pos != Pos.ADJ && it.pos != Pos.ADV)
                continue;
            String w = it.source.toLowerCase(Locale.ROOT);
            String suffix;
            if (w.endsWith("est") && w.length() > 5)
                suffix = " nhất";
            else
                if (w.endsWith("er") && w.length() > 4)
                    suffix = " hơn";
                else
                    continue;
            // Phải là từ không có mục riêng, tức là tra được nhờ lemma hoá. Không dùng
            // resolve().isPresent() vì resolve() tự lemma hoá nên luôn true.
            var resolved = lookup.resolve(w);
            if (resolved.isEmpty() || !resolved.get().viaLemma())
                continue;
            it.vi = it.vi + suffix;
        }
    }

    private static void applyGrammarRules(List<Item> items)
    {
        mergeAdverbIntoAdjective(items);
        mergeNegation(items);
        fixNegatedDegree(items);
        handleAuxiliaries(items);
        markTense(items);
        reorderNounPhrases(items);
    }

    // Gộp trạng từ mức độ vào tính từ đứng sau thành MỘT đơn vị: không gộp thì sắp lại danh
    // ngữ đẩy tính từ ra sau danh từ còn trạng từ ở lại ("a very good book" -> "rất sách tốt").
    private static void mergeAdverbIntoAdjective(List<Item> items)
    {
        for (int i = 0; i < items.size(); i++)
        {
            Item adv = items.get(i);
            if (adv.dropped || !adv.isFunc(FunctionWords.Category.ADVERB))
                continue;
            Item next = nextLive(items, i);
            if (next == null || next.pos != Pos.ADJ)
                continue;
            next.vi = adv.vi + " " + next.vi;
            adv.dropped = true;
        }
    }

    // "could not" -> "không thể", "did not" -> "không", "has not" -> "chưa".
    private static void mergeNegation(List<Item> items)
    {
        for (int i = 1; i < items.size(); i++)
        {
            Item neg = items.get(i);
            if (!neg.isFunc(FunctionWords.Category.NEGATION))
                continue;
            Item prev = previousLive(items, i);
            if (prev == null || prev.fw == null)
                continue;
            switch (prev.fw.cat())
            {
                case MODAL -> {
                    // Không ghép máy móc thành "không có thể".
                    prev.vi = prev.vi.equals("có thể") ? "không thể" : "không " + prev.vi;
                    neg.dropped = true;
                }
                case FUTURE -> {
                    prev.vi = prev.vi + " không";
                    neg.dropped = true;
                }
                case BE, DO -> {
                    prev.vi = "không";
                    prev.negated = true;
                    neg.dropped = true;
                }
                case HAVE -> {
                    prev.vi = "chưa";
                    prev.negated = true;
                    neg.dropped = true;
                }
                default -> {
                }
            }
        }
    }

    // "not very difficult" -> "không khó lắm": tiếng Việt đẩy mức độ ra sau khi có phủ định.
    private static void fixNegatedDegree(List<Item> items)
    {
        for (int i = 0; i < items.size(); i++)
        {
            Item it = items.get(i);
            if (it.dropped || !(it.negated || it.isFunc(FunctionWords.Category.NEGATION)))
                continue;
            Item next = nextLive(items, i);
            if (next == null || next.vi == null || !next.vi.startsWith("rất "))
                continue;
            next.vi = next.vi.substring(4) + " lắm";
        }
    }

    // to be trước tính từ thì tiếng Việt không cần hệ từ ("the system is old" -> "hệ thống cũ");
    // trước động từ -ing thì thành "đang".
    private static void handleAuxiliaries(List<Item> items)
    {
        for (int i = 0; i < items.size(); i++)
        {
            Item it = items.get(i);
            if (it.dropped)
                continue;
            Item next = nextLive(items, i);
            if (next == null)
                continue;

            if (it.negated)
                continue; // "does not" -> "không", giữ lại

            if (it.isFunc(FunctionWords.Category.BE))
            {
                if (next.pos == Pos.VERB && next.gerund)
                    it.vi = "đang";
                else
                    if (next.pos == Pos.ADJ)
                        it.dropped = true;
            }
            else if (it.isFunc(FunctionWords.Category.DO) && next.pos == Pos.VERB)
            {
                it.dropped = true; // trợ động từ rỗng
            }
            else
                if (it.isFunc(FunctionWords.Category.PREPOSITION) && it.source.equalsIgnoreCase("to")
                        && next.pos == Pos.VERB)
            {
                it.dropped = true; // to-infinitive, không phải "đến"
            }
        }
    }

    // Chèn "đã" trước động từ quá khứ, trừ khi ngay trước đã có dấu hiệu thì.
    private static void markTense(List<Item> items)
    {
        for (int i = 0; i < items.size(); i++)
        {
            Item it = items.get(i);
            if (it.dropped || it.pos != Pos.VERB || !it.past)
                continue;
            Item prev = previousLive(items, i);
            if (prev != null && (prev.isFunc(FunctionWords.Category.HAVE, FunctionWords.Category.FUTURE,
                    FunctionWords.Category.MODAL, FunctionWords.Category.BE, FunctionWords.Category.NEGATION)))
            {
                continue; // "had gone", "will go", "không thể"
            }
            it.vi = "đã " + it.vi;
        }
    }

    // Sắp lại danh ngữ theo trật tự tiếng Việt:
    // Anh: [mạo từ] [lượng từ] [tính từ] DANH TỪ the old system
    // Việt: [lượng từ] DANH TỪ [tính từ] [chỉ định] [sở hữu] hệ thống cũ này của tôi
    // Quét một cụm liên tục gồm mạo từ/lượng từ/sở hữu/chỉ định/tính từ kết thúc bằng danh từ
    // rồi phát lại; giới từ, động từ, dấu câu cắt cụm ("number of users" không trộn làm một).
    private static void reorderNounPhrases(List<Item> items)
    {
        int i = 0;
        while (i < items.size())
        {
            int start = i;
            int lastNoun = -1;
            int j = i;
            while (j < items.size() && inNounPhrase(items.get(j)))
            {
                // Từ hạn định sau khi đã có danh từ là danh ngữ MỚI: "Vietnamese every day".
                if (lastNoun >= 0 && isDeterminer(items.get(j)))
                    break;
                if (items.get(j).pos == Pos.NOUN)
                    lastNoun = j;
                j++;
            }
            if (lastNoun < 0 || lastNoun == start)
            {
                i = Math.max(j, i + 1);
                continue;
            }
            List<Item> span = new ArrayList<>(items.subList(start, lastNoun + 1));
            // articles (đã bị bỏ) vẫn phải nằm trong danh sách phát lại: số ô phải khớp ô cũ,
            // nếu không ô cuối còn giữ item cũ và từ bị lặp.
            List<Item> articles = new ArrayList<>();
            List<Item> quantifiers = new ArrayList<>();
            List<Item> nouns = new ArrayList<>();
            List<Item> adjectives = new ArrayList<>();
            List<Item> demonstratives = new ArrayList<>();
            List<Item> possessives = new ArrayList<>();
            for (Item it : span)
            {
                if (it.isFunc(FunctionWords.Category.ARTICLE))
                {
                    it.dropped = true;
                    articles.add(it);
                }
                else
                    if (it.isFunc(FunctionWords.Category.QUANTIFIER))
                        quantifiers.add(it);
                    else
                        if (it.isFunc(FunctionWords.Category.DEMONSTRATIVE))
                            demonstratives.add(it);
                        else
                            if (it.isFunc(FunctionWords.Category.POSSESSIVE))
                                possessives.add(it);
                            else
                                if (it.pos == Pos.ADJ)
                                    adjectives.add(it);
                                else
                                    nouns.add(it);
            }
            List<Item> rebuilt = new ArrayList<>(span.size());
            rebuilt.addAll(articles);
            rebuilt.addAll(quantifiers);
            rebuilt.addAll(nouns);
            rebuilt.addAll(adjectives);
            rebuilt.addAll(demonstratives);
            rebuilt.addAll(possessives);
            if (rebuilt.size() != span.size())
            {
                throw new IllegalStateException(
                        "noun phrase reordering lost items: " + span.size() + " -> " + rebuilt.size());
            }
            for (int k = 0; k < rebuilt.size(); k++)
                items.set(start + k, rebuilt.get(k));

            i = lastNoun + 1;
        }
    }

    private static boolean isDeterminer(Item it)
    {
        return it.isFunc(FunctionWords.Category.ARTICLE, FunctionWords.Category.QUANTIFIER,
                FunctionWords.Category.POSSESSIVE, FunctionWords.Category.DEMONSTRATIVE);
    }

    private static boolean inNounPhrase(Item it)
    {
        if (it.dropped)
            return false;
        if (it.pos == Pos.NOUN || it.pos == Pos.ADJ)
            return true;
        return it.isFunc(FunctionWords.Category.ARTICLE, FunctionWords.Category.QUANTIFIER,
                FunctionWords.Category.POSSESSIVE, FunctionWords.Category.DEMONSTRATIVE);
    }

    private static Item previousLive(List<Item> items, int from)
    {
        for (int i = from - 1; i >= 0; i--)
        {
            if (!items.get(i).dropped)
                return items.get(i);
        }
        return null;
    }

    private static Item nextLive(List<Item> items, int from)
    {
        for (int i = from + 1; i < items.size(); i++)
        {
            if (!items.get(i).dropped)
                return items.get(i);
        }
        return null;
    }

    private static String join(List<Item> items)
    {
        StringBuilder sb = new StringBuilder(96);
        for (Item it : items)
        {
            if (it.dropped)
                continue;
            String piece = it.vi == null ? "" : it.vi.trim();
            if (piece.isEmpty())
                continue;
            if (it.pos == Pos.PUNCT)
            {
                sb.append(piece); // dấu câu dính sát từ trước
            }
            else
            {
                if (!sb.isEmpty())
                    sb.append(' ');
                sb.append(piece);
            }
        }
        String out = sb.toString().replaceAll("\\s+", " ").trim();
        if (out.isEmpty())
            return out;
        return Character.toUpperCase(out.charAt(0)) + out.substring(1);
    }
}
