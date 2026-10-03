package com.anhtuan.dict.core.index;

// Xếp hạng theo BM25, cùng công thức "search theo độ liên quan" mà Lucene dùng, gọn trong một file.
public final class BM25Scorer
{

    // Độ bão hòa tần suất từ; 1.2 là giá trị chuẩn của Lucene.
    public static final double K1 = 1.2;
    // Mức phạt văn bản dài; 0.75 là giá trị chuẩn cho văn bản thông thường.
    public static final double B = 0.75;
    // Hệ số b dùng cho từ điển. Đo khi gõ "chăm sóc" với b = 0,75 thì top toàn từ hiếm
    // (herdsman, tend, horse-hoe, childminding, loving-kindness). Nguyên nhân: b càng lớn BM25 càng phạt
    // tài liệu dài; với từ điển thì ngược lại, tài liệu dài là từ quan trọng (care có 26 nghĩa và ví dụ,
    // horse-hoe có 1). Hạ b xuống 0,2 đưa care lên đầu; đây là chỗ duy nhất lệch khỏi giá trị chuẩn.
    public static final double DICTIONARY_B = 0.2;

    private final int docCount;
    private final double avgDocLength;
    private final double b;

    public BM25Scorer(int docCount, double avgDocLength)
    {
        this(docCount, avgDocLength, B);
    }

    public BM25Scorer(int docCount, double avgDocLength, double b)
    {
        if (docCount <= 0)
            throw new IllegalArgumentException("docCount must be > 0");
        if (avgDocLength <= 0)
            throw new IllegalArgumentException("avgDocLength must be > 0");
        this.docCount = docCount;
        this.avgDocLength = avgDocLength;
        this.b = b;
    }

    // idf(t) = ln(1 + (N - df + 0.5) / (df + 0.5)); term hiếm thì idf cao, công thức này luôn dương nên không có điểm
    // âm.
    public double idf(int docFreq)
    {
        return Math.log(1.0 + (docCount - docFreq + 0.5) / (docFreq + 0.5));
    }

    // Điểm của một term trong một document; cộng dồn qua các term để ra điểm cuối.
    public double score(int docFreq, int termFreq, int docLength)
    {
        double idf = idf(docFreq);
        double norm = K1 * (1 - b + b * docLength / avgDocLength);
        return idf * (termFreq * (K1 + 1)) / (termFreq + norm);
    }

    // Hệ số boost: thiếu nó thì gõ "đi" sẽ ra một cụm từ hiếm trước cả từ "đi".
    public static double boost(boolean exactFullGloss, boolean inPrimaryGloss, boolean singleWord, int headwordLength)
    {
        double f = 1.0;
        if (exactFullGloss)
            f *= 3.0;
        if (inPrimaryGloss)
            f *= 1.8;
        if (singleWord)
            f *= 1.4;
        if (headwordLength <= 6)
            f *= 1.2;
        return f;
    }
}
