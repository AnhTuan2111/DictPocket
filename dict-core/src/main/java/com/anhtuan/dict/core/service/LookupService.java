package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.model.Candidate;
import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.Lemmatizer;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.source.SourceCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// Tra từ Anh->Việt với bậc thang fallback: tra thẳng headwordNorm, trượt thì thử các ứng viên
// lemma (went->go, running->run), vẫn trượt thì rỗng (người gọi coi là UNKNOWN). Mỗi ứng viên
// của Lemmatizer#candidates được đối chiếu với từ điển nên không cần file lemma riêng.
public final class LookupService
{

    private static final int MAX_CANDIDATES = 12;

    private final PackReader pack;
    // Đổi được lúc đang chạy khi người dùng bật/tắt nguồn nên phải volatile.
    private volatile SourceCatalog catalog;

    public LookupService(PackReader pack)
    {
        this(pack, null);
    }

    public LookupService(PackReader pack, SourceCatalog catalog)
    {
        this.pack = pack;
        this.catalog = catalog;
    }

    public void setCatalog(SourceCatalog catalog)
    {
        this.catalog = catalog;
    }

    public SourceCatalog catalog()
    {
        return catalog;
    }

    // Bỏ mục thuộc nguồn đang TẮT và xếp nguồn ưu tiên hơn lên trước. Lọc lúc tra (Entry đã
    // mang sourceId) nên bật/tắt không phải sinh lại dữ liệu. Tắt hết mọi nguồn thì coi như
    // không lọc, để màn hình không trống.
    private List<Entry> applyCatalog(List<Entry> entries)
    {
        SourceCatalog current = catalog;
        if (current == null || entries.size() <= 1 && current.hasEnabled()
                && entries.stream().allMatch(e -> current.isEnabled(e.sourceId())))
        {
            return entries;
        }
        if (!current.hasEnabled())
            return entries;
        List<Entry> kept = new ArrayList<>(entries.size());
        for (Entry e : entries)
            if (current.isEnabled(e.sourceId()))
                kept.add(e);
        if (kept.isEmpty())
            return List.of();
        kept.sort(Comparator.comparingInt(e -> current.priorityOf(e.sourceId())));
        return kept;
    }

    // viaLemma: phải lemma hoá mới tìm ra (UI hiện "<- gave").
    public record Resolution(String key, List<Entry> entries, boolean viaLemma)
    {
        public Entry first()
        {
            return entries.getFirst();
        }
    }

    // Empty nghĩa là từ điển không có từ này.
    public Optional<Resolution> resolve(String word)
    {
        String norm = TextNormalizer.normalizeHeadword(word);
        if (norm.isEmpty())
            return Optional.empty();

        List<Entry> direct = applyCatalog(pack.lookupAll(norm));
        if (hasGloss(direct))
            return Optional.of(new Resolution(norm, direct, false));

        // Entry chỉ có dòng '+' tham chiếu chéo vẫn tính là trượt (vd "@went + thời quá khứ của
        // go"): phải lemma hoá tiếp để ra "@go", nếu không UI in ra ô trống.
        for (String cand : Lemmatizer.candidates(norm))
        {
            List<Entry> hit = applyCatalog(pack.lookupAll(cand));
            if (hasGloss(hit))
                return Optional.of(new Resolution(cand, hit, true));
        }
        // Hết đường: trả entry rỗng còn hơn không trả gì (còn crossRefs để hiện).
        return direct.isEmpty() ? Optional.empty() : Optional.of(new Resolution(norm, direct, false));
    }

    // Nghĩa hỏng của nguồn: còn sót markup ("<vt> vg rất tốt") hoặc rỗng. Dùng chung cho engine
    // dịch và bộ lọc khớp cụm.
    public static boolean isJunkGloss(String gloss)
    {
        return gloss == null || gloss.isBlank() || gloss.indexOf('<') >= 0;
    }

    // Số nhỏ hơn = nguồn xếp trên; candidate không thuộc nguồn nào xếp sau cùng.
    public int priorityOf(Candidate candidate)
    {
        SourceCatalog current = catalog;
        if (current == null || candidate.sourceId() == Candidate.NO_SOURCE)
            return Integer.MAX_VALUE;
        return current.priorityOf(candidate.sourceId());
    }

    private static boolean hasGloss(List<Entry> entries)
    {
        for (Entry e : entries)
        {
            for (Sense s : e.senses())
                if (!s.glosses().isEmpty())
                    return true;
            for (Idiom i : e.idioms())
                if (!i.glosses().isEmpty())
                    return true;
        }
        return false;
    }

    // Mọi entry đồng âm của một từ, không lemma hoá (cho ô tra từ của UI).
    public List<Entry> lookupAll(String word)
    {
        return applyCatalog(pack.lookupAll(word));
    }

    public boolean contains(String normalizedKey)
    {
        return pack.contains(normalizedKey);
    }

    public List<String> suggest(String prefix, int limit)
    {
        return pack.prefixScan(prefix, limit);
    }

    public PackReader pack()
    {
        return pack;
    }

    // Nghĩa đầu của từng sense trước, rồi mới đến các nghĩa còn lại, để bấm "đổi nghĩa" ở UI
    // nhảy giữa các từ loại khác nhau trước.
    public List<Candidate> candidatesOf(List<Entry> entries)
    {
        List<Candidate> primary = new ArrayList<>(MAX_CANDIDATES);
        List<Candidate> rest = new ArrayList<>(MAX_CANDIDATES);
        for (Entry e : entries)
        {
            for (Sense s : e.senses())
            {
                // Độ "dày" của nhóm nghĩa: từ điển viết kỹ nghĩa nào thì đó là nghĩa hay dùng
                // (@school: "đàn cá" 1 nghĩa/0 ví dụ thua "trường học" 6 nghĩa). Nguồn ưu tiên
                // hơn thắng tuyệt đối; chỉ so độ dày trong cùng nguồn.
                SourceCatalog current = catalog;
                int sourcePenalty = current == null ? 0 : Math.min(current.priorityOf(e.sourceId()), 100) * 1000;
                double weight = s.glosses().size() + s.examples().size() - sourcePenalty;
                String g = s.primaryGloss();
                if (g != null)
                    primary.add(new Candidate(e.headword(), g, s.pos(), weight, e.sourceId()));
                List<String> glosses = s.glosses();
                for (int i = 1; i < glosses.size(); i++)
                {
                    rest.add(new Candidate(e.headword(), glosses.get(i), s.pos(), weight - i * 0.01, e.sourceId()));
                }
            }
        }
        primary.sort(Comparator.comparingDouble(Candidate::score).reversed());
        rest.sort(Comparator.comparingDouble(Candidate::score).reversed());

        List<Candidate> out = new ArrayList<>(MAX_CANDIDATES);
        out.addAll(primary);
        for (Candidate c : rest)
        {
            if (out.size() >= MAX_CANDIDATES)
                break;
            out.add(c);
        }
        return out.size() <= MAX_CANDIDATES ? out : out.subList(0, MAX_CANDIDATES);
    }

    // Nghĩa cho một CỤM đã khớp: "give up" khớp qua khoá bị đánh nên entry là "@give", lấy nghĩa
    // đầu sẽ ra "cho, tặng" thay vì "từ bỏ"; phải tìm đúng dòng "!to give up" trong entry.
    public List<Candidate> phraseCandidatesOf(List<Entry> entries, String phraseKey)
    {
        List<Candidate> out = new ArrayList<>(MAX_CANDIDATES);
        double score = 1.0;
        for (Entry e : entries)
        {
            for (Idiom idiom : e.idioms())
            {
                if (!matchesPhrase(idiom.phrase(), phraseKey))
                    continue;
                for (String g : idiom.glosses())
                {
                    if (out.size() >= MAX_CANDIDATES)
                        break;
                    out.add(new Candidate(idiom.phrase(), g, null, score, e.sourceId()));
                    score *= 0.95;
                }
            }
        }
        if (!out.isEmpty())
            return out;
        // Cụm là headword thật (vd "a la carte"): dùng nghĩa của sense như từ đơn.
        return candidatesOf(entries);
    }

    private static boolean matchesPhrase(String idiomPhrase, String phraseKey)
    {
        String norm = TextNormalizer.normalizeHeadword(idiomPhrase);
        return norm.equals(phraseKey) || norm.equals("to " + phraseKey);
    }
}
