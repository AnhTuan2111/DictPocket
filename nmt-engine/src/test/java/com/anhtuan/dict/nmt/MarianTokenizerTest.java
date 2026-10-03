package com.anhtuan.dict.nmt;

import com.anhtuan.dict.core.model.SegmentKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

// Phần lớn test chạy trên từ vựng mini tự dựng trong @TempDir nên không cần mô hình 98 MB;
// riêng phép thử dịch thật sẽ bị bỏ qua nếu chưa tải mô hình.
class MarianTokenizerTest
{

    @TempDir
    Path tmp;

    // Từ vựng mini; điểm cao = mảnh hay gặp. Cố ý để "▁unbelievable" có hai cách cắt, để kiểm
    // tra Viterbi chọn cách TỔNG ĐIỂM cao nhất chứ không phải cách tham lam dài nhất.
    private Path miniVocab() throws IOException
    {
        List<String> lines = List.of("</s>\t0.0", // id 0
                "<unk>\t0.0", // id 1
                "▁the\t-3.0", // id 2
                "▁government\t-5.0", // id 3
                "▁un\t-6.0", // id 4
                "bel\t-9.0", // id 5
                "iev\t-9.0", // id 6
                "able\t-4.0", // id 7
                "▁unbeliev\t-12.0", // id 8
                ".\t-2.0"); // id 9
        Path file = tmp.resolve(MarianTokenizer.VOCAB_FILE);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    @DisplayName("encoding must end with </s>, as Marian expects")
    void encodeAppendsEos() throws IOException
    {
        MarianTokenizer tok = MarianTokenizer.load(miniVocab());
        long[] ids = tok.encode("the government");
        assertEquals(3, ids.length);
        assertEquals(2, ids[0]);
        assertEquals(3, ids[1]);
        assertEquals(MarianTokenizer.EOS_ID, ids[2], "a missing </s> makes the model translate everything wrong");
    }

    @Test
    @DisplayName("Viterbi picks the segmentation with the highest total score")
    void viterbiPicksHighestTotalScore() throws IOException
    {
        MarianTokenizer tok = MarianTokenizer.load(miniVocab());
        long[] ids = tok.encode("unbelievable");
        // "▁un|bel|iev|able" = -28 còn "▁unbeliev|able" = -16, nên cách thứ hai thắng;
        // đây chính là chỗ thuật toán tham lam sai.
        assertEquals(List.of(8L, 7L, 0L), List.of(ids[0], ids[1], ids[2]), "must choose ▁unbeliev + able");
    }

    @Test
    @DisplayName("unknown characters do not break the sentence, they just become <unk>")
    void unknownCharactersBecomeUnk() throws IOException
    {
        MarianTokenizer tok = MarianTokenizer.load(miniVocab());
        long[] ids = tok.encode("the ☃");
        assertEquals(2, ids[0]);
        assertTrue(ids.length >= 3);
        assertEquals(MarianTokenizer.EOS_ID, ids[ids.length - 1]);
    }

    @Test
    @DisplayName("decoding drops the ▁ character and special ids")
    void decodeRestoresSpaces() throws IOException
    {
        MarianTokenizer tok = MarianTokenizer.load(miniVocab());
        assertEquals("the government", tok.decode(List.of(2, 3, MarianTokenizer.EOS_ID)));
        assertEquals("", tok.decode(List.of(MarianTokenizer.EOS_ID)));
    }

    @Test
    @DisplayName("isInstalled() is false when the model is missing, without throwing")
    void missingModelIsDetected()
    {
        assertFalse(OnnxNmtEngine.isInstalled(tmp.resolve("missing")));
    }

    @Test
    @DisplayName("translates with the real model (skipped if the model is not downloaded)")
    void translatesWithRealModel()
    {
        Path modelDir = Path.of("..", "data", "build", OnnxNmtEngine.MODEL_DIR);
        assumeTrue(OnnxNmtEngine.isInstalled(modelDir),
                "model not downloaded - run scripts/tai-model-nmt.ps1 to enable this test");

        try (OnnxNmtEngine engine = OnnxNmtEngine.load(modelDir))
        {
            var segments = engine.translate("She went to the market yesterday.");
            assertEquals(1, segments.size(), "the sentence engine returns exactly one segment");
            assertEquals(SegmentKind.TRANSLATED, segments.getFirst().kind());
            String vi = segments.getFirst().displayGloss();
            assertTrue(vi.toLowerCase().contains("chợ"), "translation must contain 'chợ': " + vi);
        }
    }
}
