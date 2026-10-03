package com.anhtuan.dict.nmt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Tách từ cho mô hình Marian (opus-mt), viết thuần Java.
// Tự viết vì thư viện HuggingFace `tokenizers` (qua DJL) làm sập cả JVM trong native code khi
// đọc tokenizer.json của model này (precompiled_charsmap = null). Phần cần làm khá đơn giản:
//   normalizer     : Precompiled với charsmap = null  -> không chuẩn hóa gì
//   pre_tokenizer  : WhitespaceSplit + Metaspace("▁") -> cắt theo khoảng trắng, thêm "▁"
//   model          : Unigram, 53.685 mục (mảnh, điểm) -> Viterbi chọn cách cắt tốt nhất
//   post_processor : thêm "</s>" ở cuối
// Mỗi mảnh có điểm (log xác suất); chọn cách cắt có TỔNG ĐIỂM CAO NHẤT bằng quy hoạch động.
// Ví dụ "unbelievable" có thể thành "▁un|bel|iev|able" hoặc "▁unbeliev|able".
public final class MarianTokenizer
{

    // Ký tự thay cho khoảng trắng trong SentencePiece.
    private static final char META = '▁';

    // Các id đặc biệt, lấy từ config.json của model.
    public static final int EOS_ID = 0;
    public static final int UNK_ID = 1;
    public static final int PAD_ID = 53684;
    public static final int DECODER_START_ID = PAD_ID;

    // Tên file từ vựng dạng bảng, sinh bởi scripts/tai-model-nmt.ps1.
    public static final String VOCAB_FILE = "vocab.tsv";

    private final Map<String, Integer> pieceToId;
    private final String[] idToPiece;
    private final float[] scores;
    private final int maxPieceLength;

    private MarianTokenizer(Map<String, Integer> pieceToId, String[] idToPiece, float[] scores, int maxPieceLength)
    {
        this.pieceToId = pieceToId;
        this.idToPiece = idToPiece;
        this.scores = scores;
        this.maxPieceLength = maxPieceLength;
    }

    // Đọc vocab.tsv: mỗi dòng là "mảnh<TAB>điểm", số thứ tự dòng chính là id.
    public static MarianTokenizer load(Path vocabFile)
    {
        List<String> pieces = new ArrayList<>(60_000);
        List<Float> scoreList = new ArrayList<>(60_000);
        int maxLen = 1;
        try (BufferedReader r = Files.newBufferedReader(vocabFile, StandardCharsets.UTF_8))
        {
            String line;
            while ((line = r.readLine()) != null)
            {
                int tab = line.lastIndexOf('\t');
                if (tab < 0)
                    continue;
                String piece = line.substring(0, tab);
                pieces.add(piece);
                scoreList.add(Float.parseFloat(line.substring(tab + 1)));
                maxLen = Math.max(maxLen, piece.length());
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read " + vocabFile, e);
        }

        String[] idToPiece = pieces.toArray(new String[0]);
        float[] scores = new float[scoreList.size()];
        Map<String, Integer> pieceToId = new HashMap<>(idToPiece.length * 2);
        for (int i = 0; i < idToPiece.length; i++)
        {
            scores[i] = scoreList.get(i);
            pieceToId.putIfAbsent(idToPiece[i], i);
        }
        return new MarianTokenizer(pieceToId, idToPiece, scores, maxLen);
    }

    public int vocabSize()
    {
        return idToPiece.length;
    }

    // Câu tiếng Anh -> dãy id, đã thêm </s> ở cuối như Marian mong đợi.
    public long[] encode(String text)
    {
        List<Integer> ids = new ArrayList<>(32);
        for (String word : text.trim().split("\\s+"))
        {
            if (word.isEmpty())
                continue;
            viterbi(META + word, ids);
        }
        ids.add(EOS_ID);
        long[] out = new long[ids.size()];
        for (int i = 0; i < out.length; i++)
            out[i] = ids.get(i);
        return out;
    }

    // Cắt một từ thành các mảnh sao cho TỔNG ĐIỂM cao nhất. best[i] là điểm tốt nhất để phủ hết
    // word[0..i); với mỗi vị trí thử mọi mảnh bắt đầu từ đó, rồi lần ngược theo from[] lấy dãy mảnh.
    private void viterbi(String word, List<Integer> out)
    {
        int n = word.length();
        double[] best = new double[n + 1];
        int[] from = new int[n + 1];
        int[] pieceAt = new int[n + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        java.util.Arrays.fill(from, -1);
        best[0] = 0;

        for (int i = 0; i < n; i++)
        {
            if (best[i] == Double.NEGATIVE_INFINITY)
                continue;
            int limit = Math.min(n, i + maxPieceLength);
            for (int j = i + 1; j <= limit; j++)
            {
                Integer id = pieceToId.get(word.substring(i, j));
                if (id == null)
                    continue;
                double score = best[i] + scores[id];
                if (score > best[j])
                {
                    best[j] = score;
                    from[j] = i;
                    pieceAt[j] = id;
                }
            }
            // Đường lui: ký tự không có trong từ vựng -> một ký tự = một <unk>, phạt nặng để
            // Viterbi chỉ chọn khi không còn cách nào khác.
            if (best[i + 1] == Double.NEGATIVE_INFINITY)
            {
                best[i + 1] = best[i] - 100;
                from[i + 1] = i;
                pieceAt[i + 1] = UNK_ID;
            }
        }

        List<Integer> reversed = new ArrayList<>(8);
        int at = n;
        while (at > 0 && from[at] >= 0)
        {
            reversed.add(pieceAt[at]);
            at = from[at];
        }
        for (int i = reversed.size() - 1; i >= 0; i--)
            out.add(reversed.get(i));
    }

    // Dãy id -> câu tiếng Việt, bỏ các id đặc biệt.
    public String decode(List<Integer> ids)
    {
        StringBuilder sb = new StringBuilder(128);
        for (int id : ids)
        {
            if (id == EOS_ID || id == PAD_ID || id < 0 || id >= idToPiece.length)
                continue;
            sb.append(idToPiece[id]);
        }
        return sb.toString().replace(META, ' ').trim();
    }
}
