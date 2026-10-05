package com.anhtuan.dict.desktop.config;

import com.anhtuan.dict.core.index.IndexFormat;
import com.anhtuan.dict.core.index.InvertedIndex;
import com.anhtuan.dict.core.lexicon.LexicalPrior;
import com.anhtuan.dict.core.lexicon.LexiconFormat;
import com.anhtuan.dict.core.nlp.ViCompounds;
import com.anhtuan.dict.core.pack.PackReader;
import com.anhtuan.dict.core.service.DictionaryGlossEngine;
import com.anhtuan.dict.core.service.LookupService;
import com.anhtuan.dict.core.service.ReverseSearchService;
import com.anhtuan.dict.core.service.RuleBasedTranslationEngine;
import com.anhtuan.dict.core.source.SourceCatalog;
import com.anhtuan.dict.core.spi.TranslationEngine;
import com.anhtuan.dict.desktop.userdata.HistoryStore;

import java.nio.file.Path;
import java.util.Set;

// Nơi duy nhất ráp các thành phần của ứng dụng bằng tay (composition root).
// Không dùng Spring: chỉ có vài đối tượng và không cần transaction, web hay profile, trong khi Spring
// thêm ~10 MB jar, 0,5-1,5 giây khởi động (độ trễ đầu tiên người dùng cảm nhận) và làm khó jlink/jpackage.
// Ngoài DictApp, không chỗ nào khác biết đến lớp này.
public final class AppContext implements AutoCloseable
{
    private final Path dataDir;
    private final PackReader pack;
    private final InvertedIndex viIndex;
    private final InvertedIndex viNoDiacIndex;
    private final InvertedIndex trigramIndex;
    private final LexicalPrior lexicalPrior;
    private final ViCompounds compounds;
    private SourceCatalog catalog;

    private final LookupService lookupService;
    private final ReverseSearchService reverseSearchService;
    private final DictionaryGlossEngine glossEngine;
    private final RuleBasedTranslationEngine sentenceEngine;
    private final Set<String> phraseStarters;

    private final long startupMillis;
    private final HistoryStore history;

    public AppContext()
    {
        long t0 = System.nanoTime();
        this.dataDir = DataLocator.locate();
        this.pack = PackReader.open(dataDir.resolve(DataLocator.PACK_FILE));
        this.viIndex = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_INDEX));
        this.viNoDiacIndex = InvertedIndex.open(dataDir.resolve(IndexFormat.VI_NODIAC_INDEX));
        this.trigramIndex = InvertedIndex.open(dataDir.resolve(IndexFormat.TRIGRAM_INDEX));

        this.catalog = SourceCatalog.loadOrDefault(dataDir.resolve(SourceCatalog.FILE_NAME), "Anh-Việt 109K",
                pack.entryCount());
        this.lookupService = new LookupService(pack, catalog);
        // Thiếu lex.bin thì app vẫn chạy, chỉ chọn nghĩa kém hơn
        this.lexicalPrior = LexicalPrior.openIfPresent(dataDir.resolve(LexiconFormat.FILE_NAME));
        // Danh sách từ ghép phải đúng là bản sinh ra cùng lúc với index
        this.compounds = ViCompounds.loadIfPresent(dataDir.resolve(ViCompounds.FILE_NAME));
        this.reverseSearchService = new ReverseSearchService(pack, viIndex, viNoDiacIndex, trigramIndex, compounds,
                lexicalPrior);
        this.reverseSearchService.setCatalog(catalog);
        // Quét vùng KEYS một lần để lấy các từ mở đầu cụm, rẻ hơn giữ thêm một file riêng
        this.phraseStarters = pack.multiWordStarters();
        this.glossEngine = new DictionaryGlossEngine(lookupService, phraseStarters, lexicalPrior);
        this.sentenceEngine = new RuleBasedTranslationEngine(glossEngine, lookupService, lexicalPrior);

        this.startupMillis = (System.nanoTime() - t0) / 1_000_000;
        this.history = openHistory();
    }

    // Lịch sử tra nằm ở %APPDATA%\DictPocket\history.tsv, tách khỏi thư mục cài để gỡ hay cài đè không làm mất.
    // -Ddict.history=<file> đổi chỗ lưu. Lúc chụp ảnh tài liệu thì chỉ giữ trong bộ nhớ, khỏi đụng vào lịch sử thật.
    private static HistoryStore openHistory()
    {
        if (System.getProperty("dict.screenshot") != null)
        {
            return HistoryStore.inMemory();
        }
        String override = System.getProperty("dict.history");
        if (override != null && !override.isBlank())
        {
            return HistoryStore.open(Path.of(override));
        }
        String appData = System.getenv("APPDATA");
        Path base = appData != null && !appData.isBlank()
                ? Path.of(appData, "DictPocket")
                : Path.of(System.getProperty("user.home"), ".dictpocket");
        return HistoryStore.open(base.resolve("history.tsv"));
    }

    public Path dataDir()
    {
        return dataDir;
    }

    public PackReader pack()
    {
        return pack;
    }

    public LookupService lookup()
    {
        return lookupService;
    }

    public ReverseSearchService search()
    {
        return reverseSearchService;
    }

    public TranslationEngine engine()
    {
        return glossEngine;
    }

    // Khai báo kiểu cụ thể vì giao diện cần gọi glossSegments
    public RuleBasedTranslationEngine sentenceEngine()
    {
        return sentenceEngine;
    }

    // Thời gian nạp dữ liệu, hiện ở thanh trạng thái
    public long startupMillis()
    {
        return startupMillis;
    }

    public SourceCatalog catalog()
    {
        return catalog;
    }

    // Người dùng vừa bật/tắt hoặc đổi thứ tự nguồn: áp dụng ngay cho cả tra cứu lẫn tìm kiếm,
    // rồi ghi xuống đĩa để lần sau mở lên vẫn vậy
    public void updateCatalog(SourceCatalog updated)
    {
        this.catalog = updated;
        lookupService.setCatalog(updated);
        reverseSearchService.setCatalog(updated);
        updated.save(dataDir.resolve(SourceCatalog.FILE_NAME));
    }

    public HistoryStore history()
    {
        return history;
    }

    public ViCompounds compounds()
    {
        return compounds;
    }

    public LexicalPrior lexicalPrior()
    {
        return lexicalPrior;
    }

    @Override
    public void close()
    {
        // Đóng ngược thứ tự mở
        lexicalPrior.close();
        trigramIndex.close();
        viNoDiacIndex.close();
        viIndex.close();
        pack.close();
    }
}
