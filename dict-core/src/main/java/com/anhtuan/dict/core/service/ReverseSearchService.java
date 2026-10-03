package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.index.BM25Scorer;
import com.anhtuan.dict.core.index.InvertedIndex;
import com.anhtuan.dict.core.index.TrigramIndex;
import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.nlp.ViCompounds;
import com.anhtuan.dict.core.pack.PackReader;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Việt->Anh và search theo độ liên quan: searchVietnamese (BM25 trên index nghĩa tiếng Việt,
// tự chọn index có dấu/không dấu) và fuzzyEnglish (trigram + Jaccard, chỉ dùng khi tra chính
// xác đã trượt).
//
// BM25 thô không dùng được cho từ điển: nó phạt tài liệu dài (b = 0,75) mà ở đây tài liệu dài
// lại là dấu hiệu của từ quan trọng (gõ "chăm sóc" ra herdsman, horse-hoe; "care" tụt hạng).
// Nên BM25 chỉ lọc RERANK_POOL ứng viên, rồi xếp lại bằng bốn yếu tố: độ phủ truy vấn (bình
// phương), khớp nguyên cụm (x2) / đúng cả dòng nghĩa (x3), độ phủ ngược của dòng nghĩa, và độ
// nổi bật 1 + 0,25 x ln(1 + độ dài tài liệu) bù lại phần BM25 vừa phạt.
public final class ReverseSearchService
{

    // Lớn hơn = chính xác hơn nhưng chậm hơn.
    private static final int RERANK_POOL = 400;

    // Số mũ của hệ số "độ nổi bật" = docLength ^ PROMINENCE. Thay cho danh sách tần suất từ mà
    // bản offline không có: mục từ dài là mục quan trọng ("bank" 20 nghĩa vs "clearing-house" 1).
    // Đã sweep trên truy vấn thật: 0 và 0,25 không đẩy được từ phổ thông lên, 0,5 thì các mục
    // khổng lồ như take/set chen vào mọi truy vấn; 0,35 là điểm cân bằng.
    private static final double PROMINENCE = Double.parseDouble(System.getProperty("dict.prominence", "0.35"));

    private final PackReader pack;
    private final InvertedIndex viIndex;
    private final InvertedIndex viNoDiacIndex;
    private final InvertedIndex trigramIndex;
    private final ViCompounds compounds;
    private final LexicalPrior prior;
    // Đặt từ bên ngoài để kết quả tìm cũng tôn trọng việc bật/tắt nguồn.
    private volatile com.anhtuan.dict.core.source.SourceCatalog catalog;

    public ReverseSearchService(PackReader pack, InvertedIndex viIndex, InvertedIndex viNoDiacIndex,
            InvertedIndex trigramIndex)
    {
        this(pack, viIndex, viNoDiacIndex, trigramIndex, ViCompounds.empty(), LexicalPrior.empty());
    }

    public ReverseSearchService(PackReader pack, InvertedIndex viIndex, InvertedIndex viNoDiacIndex,
            InvertedIndex trigramIndex, ViCompounds compounds)
    {
        this(pack, viIndex, viNoDiacIndex, trigramIndex, compounds, LexicalPrior.empty());
    }

    // compounds phải đúng danh sách đã dùng lúc đánh chỉ mục, nếu không truy vấn sinh ra term
    // mà index không có.
    public ReverseSearchService(PackReader pack, InvertedIndex viIndex, InvertedIndex viNoDiacIndex,
            InvertedIndex trigramIndex, ViCompounds compounds, LexicalPrior prior)
    {
        this.pack = pack;
        this.viIndex = viIndex;
        this.viNoDiacIndex = viNoDiacIndex;
        this.trigramIndex = trigramIndex;
        this.compounds = compounds;
        this.prior = prior;
    }

    public void setCatalog(com.anhtuan.dict.core.source.SourceCatalog catalog)
    {
        this.catalog = catalog;
    }

    private com.anhtuan.dict.core.source.SourceCatalog lookupCatalog()
    {
        return catalog;
    }

    // display: headword, hoặc cụm thành ngữ nếu nghĩa khớp nằm ở dòng '!' (gõ "chăm sóc" hiện
    // "to look after" chứ không phải "look"). matchedGloss để UI giải thích vì sao có kết quả.
    public record Hit(Entry entry, int docId, double score, String display, String matchedGloss)
    {
    }

    // Tự chọn index: gõ có dấu dùng index có dấu, gõ không dấu dùng index không dấu.
    public List<Hit> searchVietnamese(String query, int limit)
    {
        List<String> syllables = TextNormalizer.splitTokens(query);
        if (syllables.isEmpty())
            return List.of();

        boolean hasDiacritics = !TextNormalizer.removeDiacritics(query).equals(query);
        InvertedIndex index = hasDiacritics ? viIndex : viNoDiacIndex;
        List<String> terms = hasDiacritics
                ? syllables
                : syllables.stream().map(TextNormalizer::removeDiacritics).toList();
        // Term xếp lại có thêm từ ghép: nghĩa chứa đúng "chăm sóc" phải hơn nghĩa chỉ tình cờ
        // có cả hai âm tiết ở hai chỗ khác nhau.
        List<String> rerankTerms = compounds.expand(terms);

        BM25Scorer scorer = new BM25Scorer(index.docCount(), index.avgDocLength(), BM25Scorer.DICTIONARY_B);
        Map<Integer, Double> bm25 = new HashMap<>(4096);
        Map<Integer, Integer> hitTerms = new HashMap<>(4096);
        for (String term : terms)
        {
            int df = index.docFreq(term);
            if (df == 0)
                continue;
            for (int[] posting : index.postings(term))
            {
                int docId = posting[0];
                bm25.merge(docId, scorer.score(df, posting[1], index.docLength(docId)), Double::sum);
                hitTerms.merge(docId, 1, Integer::sum);
            }
        }
        if (bm25.isEmpty())
            return List.of();

        // Lọc ứng viên: ưu tiên tuyệt đối tài liệu khớp nhiều từ truy vấn nhất, và phải nhân độ
        // nổi bật ngay từ đây. Gõ "đi" khớp ~20.000 tài liệu; cắt pool bằng BM25 thô thì @go
        // (rất dài nên bị phạt) rơi khỏi pool và không bao giờ được xếp lại.
        List<Map.Entry<Integer, Double>> pool = new ArrayList<>(bm25.entrySet());
        pool.sort(Comparator.<Map.Entry<Integer, Double>>comparingInt(e -> -hitTerms.getOrDefault(e.getKey(), 0))
                .thenComparing(Comparator.comparingDouble(
                        (Map.Entry<Integer, Double> e) -> -e.getValue() * prominence(index.docLength(e.getKey())))));
        if (pool.size() > RERANK_POOL)
            pool = pool.subList(0, RERANK_POOL);

        String joinedQuery = String.join(" ", terms);
        List<Hit> hits = new ArrayList<>(pool.size());
        for (Map.Entry<Integer, Double> e : pool)
        {
            int docId = e.getKey();
            Entry entry = pack.entryAt(docId);
            // Nguồn đang tắt thì không được xuất hiện trong kết quả.
            var catalog = lookupCatalog();
            if (catalog != null && catalog.hasEnabled() && !catalog.isEnabled(entry.sourceId()))
            {
                continue;
            }
            GlossMatch gm = bestGloss(entry, rerankTerms, hasDiacritics, joinedQuery);

            double queryCoverage = (double) hitTerms.getOrDefault(docId, 1) / terms.size();
            // Bảng xác suất dịch từ dùng theo chiều ngược: từ Anh nào hay sinh ra đúng các âm tiết
            // vừa gõ thì đang được tìm. Phân biệt "government" với "sircar" (cùng nghĩa "chính
            // phủ" nhưng chỉ một từ từng xuất hiện trong kho câu song ngữ).
            double reverse = 1 + 4.0 * prior.scoreGloss(entry.headwordNorm(), terms);
            double glossCoverage = gm.glossTokens() == 0 ? 0 : (double) gm.matchedInGloss() / gm.glossTokens();
            double prominence = prominence(index.docLength(docId));
            double factor = BM25Scorer.boost(gm.exactGloss(), gm.inPrimaryGloss(), !entry.isMultiWord(),
                    entry.headwordNorm().length());
            if (gm.phraseHit() && !gm.exactGloss())
                factor *= 2.0;

            double score = e.getValue() * queryCoverage * queryCoverage * (0.4 + 0.6 * glossCoverage) * prominence
                    * reverse * factor;
            hits.add(new Hit(entry, docId, score, gm.display() == null ? entry.headword() : gm.display(), gm.gloss()));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return dedupe(hits, limit);
    }

    private static double prominence(int docLength)
    {
        return Math.pow(1 + docLength, PROMINENCE);
    }

    // Bỏ kết quả trùng: nguồn có nhiều cặp trùng thật ("to send away" nằm trong cả @send lẫn
    // @away, "ride" và "ridden" cùng một nghĩa).
    private static List<Hit> dedupe(List<Hit> sorted, int limit)
    {
        List<Hit> out = new ArrayList<>(Math.min(limit, sorted.size()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Hit h : sorted)
        {
            if (out.size() >= limit)
                break;
            if (seen.add(h.display() + " " + h.matchedGloss()))
                out.add(h);
        }
        return out;
    }

    // Đoán từ tiếng Việt khi gõ sai chính tả; rỗng nghĩa là truy vấn vốn đã đúng.
    public List<String> suggestVietnamese(String query, int limit)
    {
        return compounds.suggest(query, limit);
    }

    // Đoán từ tiếng Anh gõ sai, xếp hạng bằng Jaccard trên trigram ký tự; chỉ gọi khi tra chính xác đã trượt.
    public List<Hit> fuzzyEnglish(String query, int limit)
    {
        String norm = TextNormalizer.normalizeHeadword(query);
        List<String> grams = TrigramIndex.trigrams(norm);
        if (grams.isEmpty())
            return List.of();

        Map<Integer, Integer> matched = new HashMap<>(4096);
        for (String g : grams)
        {
            for (int[] posting : trigramIndex.postings(g))
            {
                matched.merge(posting[0], 1, Integer::sum);
            }
        }
        List<Map.Entry<Integer, Integer>> pool = new ArrayList<>(matched.entrySet());
        pool.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed());
        if (pool.size() > RERANK_POOL)
            pool = pool.subList(0, RERANK_POOL);

        List<Hit> hits = new ArrayList<>(pool.size());
        for (Map.Entry<Integer, Integer> e : pool)
        {
            double j = TrigramIndex.jaccard(e.getValue(), grams.size(), trigramIndex.docLength(e.getKey()));
            if (j < 0.2)
                continue;
            Entry entry = pack.entryAt(e.getKey());
            hits.add(new Hit(entry, e.getKey(), j, entry.headword(), firstGloss(entry)));
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return dedupe(hits, limit);
    }

    private record GlossMatch(String gloss, String display, int matchedInGloss, int glossTokens, boolean exactGloss,
            boolean phraseHit, boolean inPrimaryGloss)
    {
    }

    // Dòng nghĩa khớp nhất của một entry, kèm các cờ để tính điểm.
    private GlossMatch bestGloss(Entry entry, List<String> terms, boolean hasDiacritics, String joinedQuery)
    {
        GlossMatch best = null;
        boolean isFirst = true;

        for (Sense s : entry.senses())
        {
            for (String gloss : s.glosses())
            {
                GlossMatch m = scoreGloss(gloss, entry.headword(), terms, hasDiacritics, joinedQuery, isFirst);
                best = better(best, m);
                isFirst = false;
            }
        }
        for (Idiom i : entry.idioms())
        {
            for (String gloss : i.glosses())
            {
                GlossMatch m = scoreGloss(gloss, i.phrase(), terms, hasDiacritics, joinedQuery, false);
                best = better(best, m);
            }
        }
        return best == null ? new GlossMatch(null, entry.headword(), 0, 0, false, false, false) : best;
    }

    private static GlossMatch better(GlossMatch a, GlossMatch b)
    {
        if (a == null)
            return b;
        if (b == null)
            return a;
        if (b.matchedInGloss() != a.matchedInGloss())
        {
            return b.matchedInGloss() > a.matchedInGloss() ? b : a;
        }
        // Cùng số từ khớp thì chọn nghĩa ngắn hơn: nó nói đúng ý người dùng hơn.
        return b.glossTokens() < a.glossTokens() ? b : a;
    }

    private GlossMatch scoreGloss(String gloss, String display, List<String> terms, boolean hasDiacritics,
            String joinedQuery, boolean isFirst)
    {
        List<String> syllables = TextNormalizer.splitTokens(gloss);
        List<String> cmp = hasDiacritics
                ? syllables
                : syllables.stream().map(TextNormalizer::removeDiacritics).toList();
        // Phía dòng nghĩa cũng phải gộp từ ghép, nếu không term "chăm_sóc" của truy vấn không
        // bao giờ khớp.
        List<String> cmpExpanded = compounds.expand(cmp);

        int matched = 0;
        for (String t : terms)
            if (cmpExpanded.contains(t))
                matched++;

        String joinedGloss = String.join(" ", cmp);
        boolean exact = joinedGloss.equals(joinedQuery);
        boolean phrase = !exact && joinedGloss.contains(joinedQuery);
        return new GlossMatch(gloss, display, matched, cmp.size(), exact, phrase, isFirst && matched > 0);
    }

    private static String firstGloss(Entry entry)
    {
        for (Sense s : entry.senses())
        {
            String g = s.primaryGloss();
            if (g != null)
                return g;
        }
        for (Idiom i : entry.idioms())
        {
            if (!i.glosses().isEmpty())
                return i.glosses().getFirst();
        }
        return null;
    }
}
