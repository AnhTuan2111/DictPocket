package com.anhtuan.dict.nmt;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.anhtuan.dict.core.model.Candidate;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.model.SegmentKind;
import com.anhtuan.dict.core.nlp.SentenceSplitter;
import com.anhtuan.dict.core.spi.TranslationEngine;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Dịch cả câu bằng mô hình nơ-ron chạy cục bộ trên CPU (opus-mt-en-vi, ~101 MB, 6 lớp encoder
// + 6 lớp decoder). Khác hai engine kia (chỉ tra từ điển và áp luật), đây là AI thật, nên UI
// phải ghi rõ điều đó cho người dùng.
// Luồng: câu Anh --MarianTokenizer--> dãy id --encoder.onnx--> vector [1, n, 512]; rồi vòng lặp
// decoder.onnx (phần đã sinh + vector) chọn từ kế tiếp bằng beam search, và giải mã ra câu Việt.
// Không dùng KV-cache: mỗi bước chạy lại decoder trên toàn bộ phần đã sinh (O(n²)) cho code
// ngắn; đo thực tế câu 15-20 từ mất khoảng 1-2 giây.
// Trả về ĐÚNG MỘT Segment kind = TRANSLATED, cùng giao ước với engine dịch bằng luật.
public final class OnnxNmtEngine implements TranslationEngine, Closeable
{

    public static final String ENGINE_ID = "onnx-opus-mt";

    // Tên thư mục model trong thư mục dữ liệu.
    public static final String MODEL_DIR = "nmt-en-vi";

    private static final int MAX_INPUT_TOKENS = 200;
    // Ngưỡng ký tự để cắt thêm một câu rất dài; ~400 ký tự tương đương dưới 150 token, an toàn so với MAX_INPUT_TOKENS
    private static final int MAX_SENTENCE_CHARS = 400;
    private static final int MAX_OUTPUT_TOKENS = 256;

    // Cấm sinh lại một dãy NO_REPEAT_NGRAM từ đã từng xuất hiện. Giải mã tham lam gặp câu không
    // đủ chủ ngữ - vị ngữ (dòng tiêu đề, gạch đầu dòng) sẽ không biết dừng và lặp một cụm đến hết
    // giới hạn: đo trên 13 câu kỹ thuật thật thì 4 câu bị lặp. Chặn n-gram lặp là cách rẻ nhất.
    private static final int NO_REPEAT_NGRAM = 3;

    // Số giả thuyết giữ song song khi giải mã; 4 là giá trị model được huấn luyện để dùng
    // (num_beams trong generation_config.json), đặt 1 là quay về greedy. Greedy chọn từ tốt nhất
    // ở từng bước nên một lựa chọn đầu câu tệ kéo cả câu đi sai: "Identify and justify the most
    // appropriate model" bị dịch thành "Và biện minh cho mô hình..." (nuốt mất "Identify").
    private static final int BEAM_SIZE = 4;

    // Hệ số phạt độ dài: chia điểm cho len^alpha trước khi so sánh các giả thuyết đã xong, nếu
    // không câu NGẮN luôn thắng vì mỗi từ thêm vào làm tổng log xác suất âm hơn. 1.0 là mặc định
    // của Marian.
    private static final double LENGTH_PENALTY = 1.0;

    private final OrtEnvironment env;
    private final OrtSession encoder;
    private final OrtSession decoder;
    private final MarianTokenizer tokenizer;
    private final long loadMillis;

    private OnnxNmtEngine(OrtEnvironment env, OrtSession encoder, OrtSession decoder, MarianTokenizer tokenizer,
            long loadMillis)
    {
        this.env = env;
        this.encoder = encoder;
        this.decoder = decoder;
        this.tokenizer = tokenizer;
        this.loadMillis = loadMillis;
    }

    // Thư mục model có đủ file không; dùng để biết có nên hiện engine này trong UI.
    public static boolean isInstalled(Path modelDir)
    {
        return Files.isRegularFile(modelDir.resolve(MarianTokenizer.VOCAB_FILE))
                && Files.isRegularFile(modelDir.resolve("onnx/encoder_model_quantized.onnx"))
                && Files.isRegularFile(modelDir.resolve("onnx/decoder_model_quantized.onnx"));
    }

    // Nạp model: tốn vài giây và ~300 MB RAM, nên chỉ gọi khi người dùng thực sự chọn engine này.
    public static OnnxNmtEngine load(Path modelDir)
    {
        if (!isInstalled(modelDir))
        {
            throw new IllegalStateException("""
                    NMT model is not installed at %s.
                    Run:  .\\scripts\\tai-model-nmt.ps1   (downloads ~101 MB, once)
                    """.formatted(modelDir.toAbsolutePath()));
        }
        long t0 = System.nanoTime();
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try
        {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.min(4, Runtime.getRuntime().availableProcessors()));
            OrtSession encoder = env.createSession(modelDir.resolve("onnx/encoder_model_quantized.onnx").toString(),
                    options);
            OrtSession decoder = env.createSession(modelDir.resolve("onnx/decoder_model_quantized.onnx").toString(),
                    options);
            MarianTokenizer tokenizer = MarianTokenizer.load(modelDir.resolve(MarianTokenizer.VOCAB_FILE));
            return new OnnxNmtEngine(env, encoder, decoder, tokenizer, (System.nanoTime() - t0) / 1_000_000);
        }
        catch (OrtException e)
        {
            throw new IllegalStateException("cannot load the ONNX model: " + e.getMessage(), e);
        }
    }

    @Override
    public String engineId()
    {
        return ENGINE_ID;
    }

    @Override
    public String displayName()
    {
        return "Dịch bằng mô hình AI chạy trên máy (opus-mt)";
    }

    public long loadMillis()
    {
        return loadMillis;
    }

    @Override
    public List<Segment> translate(String text)
    {
        if (text == null || text.isBlank())
            return List.of();
        StringBuilder vi = new StringBuilder();
        for (String sentence : SentenceSplitter.split(text))
        {
            if (!vi.isEmpty())
                vi.append(' ');
            vi.append(translateSentence(sentence));
        }
        return List.of(new Segment(text, 0, text.length(), SegmentKind.TRANSLATED,
                List.of(new Candidate(text, vi.toString(), null, 1.0))));
    }

    // Mô hình học trên từng câu: đưa cả đoạn vào thì nó bỏ câu, mất dấu chấm hoặc dừng sớm. Câu dài quá
    // sức thì cắt ở dấu phẩy thay vì để đầu vào bị cắt cụt âm thầm ở MAX_INPUT_TOKENS.
    private String translateSentence(String sentence)
    {
        List<String> pieces = SentenceSplitter.splitLong(sentence, MAX_SENTENCE_CHARS);
        if (pieces.size() == 1)
            return translateToString(sentence);
        StringBuilder vi = new StringBuilder();
        for (int i = 0; i < pieces.size(); i++)
        {
            String part = translateToString(pieces.get(i));
            // Mảnh giữa câu được thêm dấu chấm giả để mô hình dừng: đổi lại thành dấu phẩy
            boolean last = i == pieces.size() - 1;
            if (!last && part.endsWith("."))
                part = part.substring(0, part.length() - 1) + ",";
            if (!vi.isEmpty())
                vi.append(' ');
            vi.append(part);
        }
        return vi.toString();
    }

    private String translateToString(String sentence)
    {
        long[] ids = tokenizer.encode(endWithPunctuation(sentence));
        if (ids.length > MAX_INPUT_TOKENS)
            ids = Arrays.copyOf(ids, MAX_INPUT_TOKENS);

        long[] maskRow = new long[ids.length];
        Arrays.fill(maskRow, 1L);

        try (OnnxTensor inputIds = OnnxTensor.createTensor(env, new long[][]{ids});
                OnnxTensor mask = OnnxTensor.createTensor(env, new long[][]{maskRow}))
        {

            Map<String, OnnxTensor> encoderInput = new HashMap<>(4);
            encoderInput.put("input_ids", inputIds);
            encoderInput.put("attention_mask", mask);

            try (OrtSession.Result encoded = encoder.run(encoderInput))
            {
                float[][][] hidden = (float[][][]) encoded.get(0).getValue();
                return decodeBeam(hidden, maskRow);
            }
        }
        catch (OrtException e)
        {
            throw new IllegalStateException("error while running the model: " + e.getMessage(), e);
        }
    }

    // Thêm dấu chấm nếu câu chưa có dấu kết thúc: model được huấn luyện trên CÂU HOÀN CHỈNH, đưa
    // vào mảnh câu không dấu chấm thì không có tín hiệu để dừng và dễ rơi vào vòng lặp.
    private static String endWithPunctuation(String sentence)
    {
        String trimmed = sentence.strip();
        if (trimmed.isEmpty())
            return trimmed;
        char last = trimmed.charAt(trimmed.length() - 1);
        return ".!?:;\"')]".indexOf(last) >= 0 ? trimmed : trimmed + ".";
    }

    // Các từ bị cấm ở bước hiện tại vì sinh ra chúng sẽ tạo một n-gram đã có: lấy NO_REPEAT_NGRAM-1
    // từ vừa sinh, tìm dãy đó trong phần đã sinh; nếu có thì từ đi liền sau nó lần trước bị cấm.
    private static java.util.Set<Integer> bannedTokens(List<Integer> generated)
    {
        int n = NO_REPEAT_NGRAM;
        if (generated.size() < n)
            return java.util.Set.of();
        java.util.Set<Integer> banned = new java.util.HashSet<>(4);
        List<Integer> suffix = generated.subList(generated.size() - (n - 1), generated.size());
        for (int i = 0; i + n <= generated.size(); i++)
        {
            if (generated.subList(i, i + n - 1).equals(suffix))
                banned.add(generated.get(i + n - 1));
        }
        return banned;
    }

    // Một giả thuyết dịch đang được giữ.
    private record Beam(List<Integer> tokens, double score)
    {
    }

    // Giải mã bằng beam search. Mỗi bước mọi giả thuyết còn sống có CÙNG độ dài nên gom thành một
    // batch và chỉ chạy decoder một lần (chạy riêng từng cái thì chậm gấp bốn). Điểm giả thuyết là
    // tổng log xác suất; phải dùng log-softmax chứ không phải logit thô, vì logit của hai giả
    // thuyết lệch nhau một hằng số riêng nên cộng thẳng sẽ so sánh sai.
    private String decodeBeam(float[][][] encoderHidden, long[] maskRow) throws OrtException
    {
        List<Beam> alive = new ArrayList<>(BEAM_SIZE);
        alive.add(new Beam(List.of(), 0.0));
        List<Beam> finished = new ArrayList<>(BEAM_SIZE);

        for (int step = 0; step < MAX_OUTPUT_TOKENS && !alive.isEmpty(); step++)
        {
            int batch = alive.size();
            int len = alive.get(0).tokens().size() + 1;

            long[][] decoderIds = new long[batch][len];
            for (int b = 0; b < batch; b++)
            {
                decoderIds[b][0] = MarianTokenizer.DECODER_START_ID;
                List<Integer> tokens = alive.get(b).tokens();
                for (int i = 0; i < tokens.size(); i++)
                    decoderIds[b][i + 1] = tokens.get(i);
            }

            float[][][] logits;
            try (OnnxTensor decoderInput = OnnxTensor.createTensor(env, decoderIds);
                    OnnxTensor hiddenTensor = OnnxTensor.createTensor(env, tile(encoderHidden, batch));
                    OnnxTensor maskTensor = OnnxTensor.createTensor(env, tile(maskRow, batch)))
            {
                Map<String, OnnxTensor> input = new HashMap<>(4);
                input.put("input_ids", decoderInput);
                input.put("encoder_hidden_states", hiddenTensor);
                input.put("encoder_attention_mask", maskTensor);
                try (OrtSession.Result result = decoder.run(input))
                {
                    logits = (float[][][]) result.get(0).getValue();
                }
            }

            List<Beam> candidates = new ArrayList<>(batch * (BEAM_SIZE + 1));
            for (int b = 0; b < batch; b++)
            {
                Beam beam = alive.get(b);
                float[] last = logits[b][len - 1];
                double logSumExp = logSumExp(last);
                java.util.Set<Integer> banned = bannedTokens(beam.tokens());

                for (int token : topK(last, banned, BEAM_SIZE + 1))
                {
                    double score = beam.score() + (last[token] - logSumExp);
                    if (token == MarianTokenizer.EOS_ID)
                    {
                        finished.add(new Beam(beam.tokens(), score));
                    }
                    else
                    {
                        List<Integer> tokens = new ArrayList<>(beam.tokens());
                        tokens.add(token);
                        candidates.add(new Beam(tokens, score));
                    }
                }
            }

            candidates.sort((x, y) -> Double.compare(y.score(), x.score()));
            alive = new ArrayList<>(candidates.subList(0, Math.min(BEAM_SIZE, candidates.size())));

            // Dừng sớm: giả thuyết sống tốt nhất không thể đuổi kịp giả thuyết đã xong.
            if (finished.size() >= BEAM_SIZE && !alive.isEmpty())
            {
                double bestAlive = normalised(alive.get(0));
                double bestFinished = finished.stream().mapToDouble(OnnxNmtEngine::normalised).max()
                        .orElse(Double.NEGATIVE_INFINITY);
                if (bestAlive < bestFinished)
                    break;
            }
        }

        // Không giả thuyết nào kết thúc đúng hạn (câu quá dài) thì lấy giả thuyết sống tốt nhất.
        List<Beam> remaining = alive;
        Beam best = finished.stream().max((x, y) -> Double.compare(normalised(x), normalised(y)))
                .orElseGet(() -> remaining.isEmpty() ? new Beam(List.of(), 0) : remaining.get(0));
        return tokenizer.decode(best.tokens());
    }

    // Điểm đã chia cho độ dài, dùng khi so sánh các giả thuyết dài ngắn khác nhau.
    private static double normalised(Beam beam)
    {
        int length = Math.max(1, beam.tokens().size());
        return beam.score() / Math.pow(length, LENGTH_PENALTY);
    }

    private static double logSumExp(float[] values)
    {
        float max = Float.NEGATIVE_INFINITY;
        for (float v : values)
        {
            if (v > max)
                max = v;
        }
        double sum = 0;
        for (float v : values)
            sum += Math.exp(v - max);
        return max + Math.log(sum);
    }

    // k từ có điểm cao nhất, bỏ qua các từ bị cấm.
    private static List<Integer> topK(float[] logits, java.util.Set<Integer> banned, int k)
    {
        java.util.PriorityQueue<Integer> heap = new java.util.PriorityQueue<>(
                (x, y) -> Float.compare(logits[x], logits[y]));
        for (int v = 0; v < logits.length; v++)
        {
            if (v == MarianTokenizer.PAD_ID || banned.contains(v))
                continue;
            heap.add(v);
            if (heap.size() > k)
                heap.poll();
        }
        List<Integer> out = new ArrayList<>(heap);
        out.sort((x, y) -> Float.compare(logits[y], logits[x]));
        return out;
    }

    // Nhân bản vector ngữ nghĩa của câu nguồn ra batch bản để chạy một lô.
    private static float[][][] tile(float[][][] source, int batch)
    {
        float[][][] out = new float[batch][][];
        for (int b = 0; b < batch; b++)
            out[b] = source[0];
        return out;
    }

    private static long[][] tile(long[] row, int batch)
    {
        long[][] out = new long[batch][];
        for (int b = 0; b < batch; b++)
            out[b] = row;
        return out;
    }

    @Override
    public void close()
    {
        try
        {
            decoder.close();
            encoder.close();
        }
        catch (OrtException e)
        {
            throw new IllegalStateException("cannot close the ONNX session", e);
        }
    }
}
