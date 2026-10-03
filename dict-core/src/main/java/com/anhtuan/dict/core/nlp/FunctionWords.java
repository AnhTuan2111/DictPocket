package com.anhtuan.dict.core.nlp;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

// Bảng từ chức năng tiếng Anh, dịch cứng và phân loại sẵn. Nhóm này xuất hiện nhiều nhất
// mà từ điển dịch tệ nhất (vd "the" -> "cái, con, người..."), nhưng là lớp đóng (~200 từ).
// Category được bộ luật trật tự từ trong RuleBasedTranslationEngine dùng để đảo/bỏ/ghép.
public final class FunctionWords
{

    private FunctionWords()
    {
    }

    public enum Category
    {
        // Mạo từ: tiếng Việt không có, bỏ hẳn ("the old system" -> "hệ thống cũ").
        ARTICLE,
        // Lượng từ đứng trước danh từ: some, many, all.
        QUANTIFIER,
        // Chỉ định từ, đảo ra sau danh từ: "this system" -> "hệ thống này".
        DEMONSTRATIVE, PRONOUN,
        // Sở hữu, đảo ra sau danh từ: "his job" -> "công việc của anh ấy".
        POSSESSIVE,
        // to be: thường bỏ trước tính từ, giữ "là" trước danh từ.
        BE,
        // have/has/had: dấu hiệu thì hoàn thành.
        HAVE,
        // do/does/did: trợ động từ rỗng, bỏ trừ khi phủ định.
        DO, MODAL, FUTURE, NEGATION, PREPOSITION, CONJUNCTION, ADVERB,
        // there (is/are) -> "có".
        EXISTENTIAL,
        // "to" của to-infinitive, bỏ: "to leave" -> "rời đi".
        INFINITIVE
    }

    // vi rỗng nghĩa là bỏ hẳn từ này khỏi câu dịch; cat quyết định luật trật tự sẽ áp dụng.
    public record Fw(String vi, Category cat)
    {
    }

    private static final Map<String, Fw> TABLE = new HashMap<>(256);

    private static void put(String en, String vi, Category cat)
    {
        TABLE.put(en, new Fw(vi, cat));
    }

    static
    {
        // Mạo từ: bỏ hẳn.
        put("a", "", Category.ARTICLE);
        put("an", "", Category.ARTICLE);
        put("the", "", Category.ARTICLE);

        // Đại từ.
        put("i", "tôi", Category.PRONOUN);
        put("you", "bạn", Category.PRONOUN);
        put("he", "anh ấy", Category.PRONOUN);
        put("she", "cô ấy", Category.PRONOUN);
        put("it", "nó", Category.PRONOUN);
        put("we", "chúng tôi", Category.PRONOUN);
        put("they", "họ", Category.PRONOUN);
        put("me", "tôi", Category.PRONOUN);
        put("him", "anh ấy", Category.PRONOUN);
        put("us", "chúng tôi", Category.PRONOUN);
        put("them", "họ", Category.PRONOUN);
        put("myself", "chính tôi", Category.PRONOUN);
        put("himself", "chính anh ấy", Category.PRONOUN);
        put("herself", "chính cô ấy", Category.PRONOUN);
        put("themselves", "chính họ", Category.PRONOUN);
        put("itself", "chính nó", Category.PRONOUN);
        put("who", "ai", Category.PRONOUN);
        put("whom", "ai", Category.PRONOUN);
        put("what", "gì", Category.PRONOUN);
        put("everyone", "mọi người", Category.PRONOUN);
        put("everybody", "mọi người", Category.PRONOUN);
        put("everything", "mọi thứ", Category.PRONOUN);
        put("someone", "ai đó", Category.PRONOUN);
        put("somebody", "ai đó", Category.PRONOUN);
        put("something", "cái gì đó", Category.PRONOUN);
        put("nobody", "không ai", Category.PRONOUN);
        put("nothing", "không gì", Category.PRONOUN);

        // Sở hữu: đảo ra sau danh từ.
        put("my", "của tôi", Category.POSSESSIVE);
        put("your", "của bạn", Category.POSSESSIVE);
        put("his", "của anh ấy", Category.POSSESSIVE);
        put("her", "của cô ấy", Category.POSSESSIVE);
        put("its", "của nó", Category.POSSESSIVE);
        put("our", "của chúng tôi", Category.POSSESSIVE);
        put("their", "của họ", Category.POSSESSIVE);
        put("whose", "của ai", Category.POSSESSIVE);

        // Chỉ định từ: đảo ra sau danh từ.
        put("this", "này", Category.DEMONSTRATIVE);
        put("that", "đó", Category.DEMONSTRATIVE);
        put("these", "này", Category.DEMONSTRATIVE);
        put("those", "đó", Category.DEMONSTRATIVE);

        // Lượng từ.
        put("some", "một số", Category.QUANTIFIER);
        put("any", "bất kỳ", Category.QUANTIFIER);
        put("all", "tất cả", Category.QUANTIFIER);
        put("both", "cả hai", Category.QUANTIFIER);
        put("each", "mỗi", Category.QUANTIFIER);
        put("every", "mọi", Category.QUANTIFIER);
        put("many", "nhiều", Category.QUANTIFIER);
        put("much", "nhiều", Category.QUANTIFIER);
        put("few", "ít", Category.QUANTIFIER);
        put("little", "ít", Category.QUANTIFIER);
        put("several", "vài", Category.QUANTIFIER);
        put("other", "khác", Category.QUANTIFIER);
        put("another", "một cái khác", Category.QUANTIFIER);
        put("such", "như vậy", Category.QUANTIFIER);
        // Số đếm: thiếu thì bị tra từ điển như danh từ (nghĩa đầu của "four" trong nguồn là
        // "chứng khoán lãi 4 qịu...").
        put("one", "một", Category.QUANTIFIER);
        put("two", "hai", Category.QUANTIFIER);
        put("three", "ba", Category.QUANTIFIER);
        put("four", "bốn", Category.QUANTIFIER);
        put("five", "năm", Category.QUANTIFIER);
        put("six", "sáu", Category.QUANTIFIER);
        put("seven", "bảy", Category.QUANTIFIER);
        put("eight", "tám", Category.QUANTIFIER);
        put("nine", "chín", Category.QUANTIFIER);
        put("ten", "mười", Category.QUANTIFIER);
        put("eleven", "mười một", Category.QUANTIFIER);
        put("twelve", "mười hai", Category.QUANTIFIER);
        put("twenty", "hai mươi", Category.QUANTIFIER);
        put("thirty", "ba mươi", Category.QUANTIFIER);
        put("forty", "bốn mươi", Category.QUANTIFIER);
        put("fifty", "năm mươi", Category.QUANTIFIER);
        put("hundred", "trăm", Category.QUANTIFIER);
        put("thousand", "nghìn", Category.QUANTIFIER);
        put("million", "triệu", Category.QUANTIFIER);
        put("billion", "tỷ", Category.QUANTIFIER);

        // to be.
        put("am", "là", Category.BE);
        put("is", "là", Category.BE);
        put("are", "là", Category.BE);
        put("was", "là", Category.BE);
        put("were", "là", Category.BE);
        put("be", "là", Category.BE);
        put("been", "là", Category.BE);
        put("being", "là", Category.BE);

        // have / do.
        put("have", "đã", Category.HAVE);
        put("has", "đã", Category.HAVE);
        put("had", "đã", Category.HAVE);
        put("do", "", Category.DO);
        put("does", "", Category.DO);
        put("did", "", Category.DO);

        // Tình thái và tương lai.
        put("can", "có thể", Category.MODAL);
        put("could", "có thể", Category.MODAL);
        put("may", "có thể", Category.MODAL);
        put("might", "có thể", Category.MODAL);
        put("must", "phải", Category.MODAL);
        put("should", "nên", Category.MODAL);
        put("ought", "nên", Category.MODAL);
        put("need", "cần", Category.MODAL);
        put("will", "sẽ", Category.FUTURE);
        put("shall", "sẽ", Category.FUTURE);
        put("would", "sẽ", Category.FUTURE);

        // Phủ định.
        put("not", "không", Category.NEGATION);
        put("n't", "không", Category.NEGATION);
        put("no", "không", Category.NEGATION);
        put("never", "không bao giờ", Category.NEGATION);
        put("cannot", "không thể", Category.NEGATION);

        // Giới từ.
        put("of", "của", Category.PREPOSITION);
        put("in", "trong", Category.PREPOSITION);
        put("on", "trên", Category.PREPOSITION);
        put("at", "tại", Category.PREPOSITION);
        put("to", "đến", Category.PREPOSITION);
        put("for", "cho", Category.PREPOSITION);
        put("with", "với", Category.PREPOSITION);
        put("from", "từ", Category.PREPOSITION);
        put("by", "bởi", Category.PREPOSITION);
        put("about", "về", Category.PREPOSITION);
        put("into", "vào", Category.PREPOSITION);
        put("onto", "lên", Category.PREPOSITION);
        put("over", "trên", Category.PREPOSITION);
        put("under", "dưới", Category.PREPOSITION);
        put("above", "phía trên", Category.PREPOSITION);
        put("below", "phía dưới", Category.PREPOSITION);
        put("between", "giữa", Category.PREPOSITION);
        put("among", "trong số", Category.PREPOSITION);
        put("through", "qua", Category.PREPOSITION);
        put("during", "trong suốt", Category.PREPOSITION);
        // Phân từ hiện tại làm giới từ: từ điển ghi là tính từ nên bước sắp lại danh ngữ sẽ đẩy
        // chúng ra sau danh từ ("including preconditions" -> "điều kiện tiên quyết kể cả").
        put("including", "gồm cả", Category.PREPOSITION);
        put("excluding", "không tính", Category.PREPOSITION);
        put("regarding", "về", Category.PREPOSITION);
        put("concerning", "về", Category.PREPOSITION);
        put("considering", "xét đến", Category.PREPOSITION);
        put("depending on", "tuỳ theo", Category.PREPOSITION);
        put("without", "không có", Category.PREPOSITION);
        put("against", "chống lại", Category.PREPOSITION);
        put("towards", "về phía", Category.PREPOSITION);
        put("toward", "về phía", Category.PREPOSITION);
        put("upon", "trên", Category.PREPOSITION);
        put("within", "trong vòng", Category.PREPOSITION);
        put("across", "băng qua", Category.PREPOSITION);
        put("around", "quanh", Category.PREPOSITION);
        put("near", "gần", Category.PREPOSITION);
        put("off", "khỏi", Category.PREPOSITION);
        put("out", "ra", Category.PREPOSITION);
        put("up", "lên", Category.PREPOSITION);
        put("down", "xuống", Category.PREPOSITION);

        // Liên từ.
        put("and", "và", Category.CONJUNCTION);
        put("or", "hoặc", Category.CONJUNCTION);
        put("but", "nhưng", Category.CONJUNCTION);
        put("because", "bởi vì", Category.CONJUNCTION);
        put("if", "nếu", Category.CONJUNCTION);
        put("unless", "trừ khi", Category.CONJUNCTION);
        put("when", "khi", Category.CONJUNCTION);
        put("while", "trong khi", Category.CONJUNCTION);
        put("since", "kể từ khi", Category.CONJUNCTION);
        put("until", "cho đến khi", Category.CONJUNCTION);
        put("although", "mặc dù", Category.CONJUNCTION);
        put("though", "mặc dù", Category.CONJUNCTION);
        put("however", "tuy nhiên", Category.CONJUNCTION);
        put("therefore", "do đó", Category.CONJUNCTION);
        put("so", "nên", Category.CONJUNCTION);
        put("than", "hơn", Category.CONJUNCTION);
        put("as", "như", Category.CONJUNCTION);
        put("whether", "liệu", Category.CONJUNCTION);
        put("where", "nơi", Category.CONJUNCTION);
        put("why", "tại sao", Category.CONJUNCTION);
        put("how", "như thế nào", Category.CONJUNCTION);
        put("which", "cái nào", Category.CONJUNCTION);

        // Trạng từ hay gặp.
        put("very", "rất", Category.ADVERB);
        put("too", "quá", Category.ADVERB);
        put("also", "cũng", Category.ADVERB);
        put("only", "chỉ", Category.ADVERB);
        put("just", "chỉ", Category.ADVERB);
        put("still", "vẫn", Category.ADVERB);
        put("already", "đã", Category.ADVERB);
        put("yet", "chưa", Category.ADVERB);
        put("now", "bây giờ", Category.ADVERB);
        put("then", "sau đó", Category.ADVERB);
        put("always", "luôn luôn", Category.ADVERB);
        put("often", "thường", Category.ADVERB);
        put("sometimes", "đôi khi", Category.ADVERB);
        put("usually", "thường", Category.ADVERB);
        put("again", "lại", Category.ADVERB);
        put("here", "ở đây", Category.ADVERB);
        put("more", "hơn", Category.ADVERB);
        put("most", "nhất", Category.ADVERB);
        put("well", "tốt", Category.ADVERB);
        put("soon", "sớm", Category.ADVERB);
        put("today", "hôm nay", Category.ADVERB);
        put("yesterday", "hôm qua", Category.ADVERB);
        put("tomorrow", "ngày mai", Category.ADVERB);

        put("there", "có", Category.EXISTENTIAL);
    }

    public static Fw get(String word)
    {
        return word == null ? null : TABLE.get(word.toLowerCase(Locale.ROOT));
    }

    public static boolean isFunctionWord(String word)
    {
        return get(word) != null;
    }

    public static int size()
    {
        return TABLE.size();
    }
}
