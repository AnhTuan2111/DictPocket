package com.anhtuan.dict.core.service;

import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.model.Candidate;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.nlp.FunctionWords;
import com.anhtuan.dict.core.nlp.PhraseProbe;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.nlp.Tokenizer;
import com.anhtuan.dict.core.spi.TranslationEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Engine dịch câu của v1: chú giải theo cụm, KHÔNG phải bản dịch tự nhiên
// ("He gave up his job." -> He[anh ấy] gave up[từ bỏ] his[của anh ấy] job[công việc]).
// Mỗi vị trí: khớp cụm dài nhất (4 -> 3 -> 2 từ), không có thì tra từ đơn qua
// LookupService#resolve, vẫn trượt thì UNKNOWN giữ nguyên từ gốc.
public final class DictionaryGlossEngine implements TranslationEngine
{

    public static final String ENGINE_ID = "dictionary-gloss";

    private final LookupService lookup;
    private final Set<String> phraseStarters;
    private final LexicalPrior prior;

    public DictionaryGlossEngine(LookupService lookup, Set<String> phraseStarters)
    {
        this(lookup, phraseStarters, LexicalPrior.empty());
    }

    // prior dùng để xếp lại thứ tự nghĩa, nếu không chú giải hiện "sự cai trị" trong khi câu
    // dịch bên trên dùng "chính phủ" và người đọc thấy app tự mâu thuẫn.
    public DictionaryGlossEngine(LookupService lookup, Set<String> phraseStarters, LexicalPrior prior)
    {
        this.lookup = lookup;
        this.phraseStarters = phraseStarters;
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
        return "Chú giải theo cụm (từ điển)";
    }

    @Override
    public List<Segment> translate(String sentence)
    {
        if (sentence == null || sentence.isEmpty())
            return List.of();

        List<Tokenizer.Token> tokens = Tokenizer.tokenize(sentence);

        // Chỉ các token là từ, kèm chỉ số token gốc để nhảy offset khi khớp cụm.
        List<String> words = new ArrayList<>(tokens.size());
        List<Integer> wordToToken = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++)
        {
            if (tokens.get(i).isWord())
            {
                words.add(TextNormalizer.normalizeHeadword(tokens.get(i).text()));
                wordToToken.add(i);
            }
        }

        List<Segment> out = new ArrayList<>(tokens.size());
        int ti = 0;
        int wi = 0;
        while (ti < tokens.size())
        {
            Tokenizer.Token t = tokens.get(ti);
            if (!t.isWord())
            {
                out.add(new Segment(t.text(), t.start(), t.end(), SegmentKind.PUNCT, List.of()));
                ti++;
                continue;
            }

            PhraseProbe.Match match = PhraseProbe.longestMatch(words, wi, phraseStarters, lookup::contains);
            if (match != null)
            {
                var phraseEntries = lookup.lookupAll(match.key());
                List<Candidate> candidates = phraseEntries.isEmpty()
                        ? List.of()
                        : lookup.phraseCandidatesOf(phraseEntries, match.key());
                // Cụm chỉ có nghĩa rác thì coi như không khớp: tra từng từ còn hơn đưa ra chuỗi
                // vô nghĩa ("very good" -> "<vt> vg rất tốt" trong nguồn).
                if (candidates.stream().allMatch(c -> LookupService.isJunkGloss(c.gloss())))
                {
                    match = null;
                }
                if (match != null)
                {
                    int lastWord = wi + match.wordCount() - 1;
                    Tokenizer.Token lastToken = tokens.get(wordToToken.get(lastWord));
                    out.add(new Segment(sentence.substring(t.start(), lastToken.end()), t.start(), lastToken.end(),
                            SegmentKind.PHRASE, candidates));
                    ti = wordToToken.get(lastWord) + 1;
                    wi = lastWord + 1;
                    continue;
                }
            }

            Optional<LookupService.Resolution> res = lookup.resolve(words.get(wi));
            if (res.isPresent())
            {
                List<Candidate> candidates = byLikelihood(t.text(), lookup.candidatesOf(res.get().entries()));
                out.add(new Segment(t.text(), t.start(), t.end(), SegmentKind.WORD,
                        withFunctionWord(t.text(), candidates)));
            }
            else
            {
                out.add(new Segment(t.text(), t.start(), t.end(), SegmentKind.UNKNOWN, List.of()));
            }
            ti++;
            wi++;
        }
        return out;
    }

    // Xếp lại các nghĩa theo độ hay dùng thật từ bảng xác suất; nghĩa không có trong bảng
    // giữ thứ tự từ điển và nằm sau.
    private List<Candidate> byLikelihood(String source, List<Candidate> candidates)
    {
        if (!prior.isAvailable() || candidates.size() < 2)
            return candidates;
        String en = source.toLowerCase(java.util.Locale.ROOT);

        record Scored(Candidate candidate, double score, int order)
        {
        }
        List<Scored> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++)
        {
            Candidate c = candidates.get(i);
            double best = 0;
            for (String alt : RuleBasedTranslationEngine.alternatives(c.gloss()))
            {
                double s = prior.scoreGloss(en, TextNormalizer.splitTokens(alt));
                if (s == 0 && c.headword() != null)
                {
                    s = prior.scoreGloss(c.headword(), TextNormalizer.splitTokens(alt));
                }
                best = Math.max(best, s);
            }
            scored.add(new Scored(c, best, i));
        }
        scored.sort((a, b) ->
        {
            // Nguồn ưu tiên hơn lên trước, rồi mới đến điểm thống kê.
            int bySource = Integer.compare(lookup.priorityOf(a.candidate()), lookup.priorityOf(b.candidate()));
            if (bySource != 0)
                return bySource;
            int cmp = Double.compare(b.score(), a.score());
            return cmp != 0 ? cmp : Integer.compare(a.order(), b.order());
        });
        List<Candidate> out = new ArrayList<>(candidates.size());
        for (Scored sc : scored)
            out.add(sc.candidate());
        return out;
    }

    // Với từ chức năng, đưa nghĩa dịch cứng lên đầu (từ điển dịch "he" là "đàn ông; con đực",
    // "the" là "cái, con, người..."); các nghĩa từ điển vẫn giữ phía sau.
    private static List<Candidate> withFunctionWord(String source, List<Candidate> dictionary)
    {
        FunctionWords.Fw fw = FunctionWords.get(source);
        if (fw == null)
            return dictionary;
        String vi = fw.vi().isEmpty()
                ? "(" + source.toLowerCase(java.util.Locale.ROOT) + " — tiếng Việt không cần dịch)"
                : fw.vi();
        List<Candidate> out = new ArrayList<>(dictionary.size() + 1);
        out.add(new Candidate(source, vi, "từ chức năng", 2.0));
        out.addAll(dictionary);
        return out;
    }

    // Nối các nghĩa mặc định thành một dòng, cho log, test và chế độ "sao chép kết quả" của UI.
    public static String flatten(List<Segment> segments)
    {
        StringBuilder sb = new StringBuilder(64);
        for (Segment s : segments)
        {
            if (s.kind() == SegmentKind.PUNCT)
            {
                sb.append(s.sourceText());
            }
            else
            {
                String gloss = s.displayGloss();
                sb.append(gloss == null ? s.sourceText() : gloss);
            }
        }
        return sb.toString();
    }
}
