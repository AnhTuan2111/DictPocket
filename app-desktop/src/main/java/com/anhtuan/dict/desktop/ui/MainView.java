package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.nlp.SentenceSplitter;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.service.ReverseSearchService;
import com.anhtuan.dict.desktop.config.AppContext;
import com.anhtuan.dict.desktop.config.AppVersion;
import com.anhtuan.dict.desktop.config.NmtSupport;
import com.anhtuan.dict.desktop.userdata.HistoryStore;
import com.anhtuan.dict.nmt.OnnxNmtEngine;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Cửa sổ chính. Ba chế độ dùng chung một ô nhập:
//   Tra từ (Anh → Việt), Dịch câu (chú giải theo cụm), Việt → Anh (xếp theo độ liên quan).
// Tra cứu chạy thẳng trên luồng giao diện vì rất nhanh (13,7 µs một lượt tra, 16 ms một lần
// tìm), tách luồng chỉ thêm phức tạp. Riêng mô hình AI mất hàng trăm ms nên chạy bằng Task.
public final class MainView
{
    private enum Mode
    {
        WORD("Tra từ (Anh → Việt)", "search"), SENTENCE("Dịch câu (Anh → Việt)", "note"), REVERSE("Việt → Anh", "chat");

        final String label;
        final String icon;

        Mode(String label, String icon)
        {
            this.label = label;
            this.icon = icon;
        }
    }

    private final AppContext ctx;
    private final TextField input = new SearchField();
    private final VBox resultHolder = new VBox();
    private final ScrollPane resultScroll = new ScrollPane(resultHolder);
    private final Label status = new Label();
    private final Region grip = new Region();
    private final Map<Mode, ToggleButton> modeButtons = new EnumMap<>(Mode.class);
    private final CheckBox useNmt = new CheckBox("Dùng mô hình AI trên máy");
    private Mode mode = Mode.WORD;

    // Engine AI nạp ở lần bật đầu tiên, null nghĩa là chưa nạp
    private OnnxNmtEngine nmtEngine;
    private boolean nmtLoading;
    // Lượt dịch AI đang chạy, để huỷ khi người dùng tra tiếp
    private Task<String> nmtTask;

    // Truy vấn mở sẵn lấy từ tham số dòng lệnh, có thể rỗng
    private final String initialQuery;

    // Gọi lại câu đã tra bằng phím ↑/↓: -1 là đang gõ dở (recallDraft giữ phần đang gõ), 0 là câu mới nhất
    private int recallIndex = -1;
    private String recallDraft = "";
    private boolean recalling;

    public MainView(AppContext ctx, List<String> args)
    {
        this.ctx = ctx;
        this.initialQuery = args == null || args.isEmpty() ? null : String.join(" ", args);
    }

    public void show(Stage stage)
    {
        status.getStyleClass().add("status");
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        grip.getStyleClass().add("grip");

        VBox content = new VBox(6);
        content.setPadding(new Insets(6));
        content.getChildren().addAll(buildToolbar(), buildInput(), buildResultArea(), new HBox(2, status, grip));

        AppIcon.applyTo(stage);
        WindowChrome chrome = WindowChrome.install(stage, AppIcon.titleIcon(), "DictPocket", content, 900, 640, true);
        chrome.bindGrip(grip);
        Scene scene = chrome.scene();

        // Ctrl+1/2/3 đổi chế độ, Ctrl+L về ô nhập: người tra từ liên tục không muốn rời bàn phím
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.WORD));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.SENTENCE));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.REVERSE));
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN), () ->
        {
            input.requestFocus();
            input.selectAll();
        });
        // Ctrl+H phải bắt bằng bộ lọc: trong ô nhập JavaFX hiểu nó là "xoá lùi một ký tự" nên phím tắt thường không tới được
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e ->
        {
            if (e.isControlDown() && e.getCode() == KeyCode.H)
            {
                e.consume();
                openHistory();
            }
        });

        stage.show();
        input.requestFocus();

        showWelcome();
        applyStartupQuery();
        Screenshot.scheduleIfRequested(scene);
    }

    // Mở sẵn một truy vấn: DictPocket.exe "give up", hoặc -Ddict.query="give up" -Ddict.mode=word.
    // Dùng để chụp ảnh tài liệu và kiểm thử tay.
    private void applyStartupQuery()
    {
        if (Boolean.getBoolean("dict.nmt") && NmtSupport.isAvailable(ctx.dataDir()))
        {
            useNmt.setSelected(true);
        }
        String query = initialQuery != null ? initialQuery : System.getProperty("dict.query");
        if (query == null || query.isBlank())
        {
            return;
        }
        String requested = System.getProperty("dict.mode", "").toLowerCase(Locale.ROOT);
        mode = switch (requested)
        {
            case "sentence" -> Mode.SENTENCE;
            case "reverse" -> Mode.REVERSE;
            case "word" -> Mode.WORD;
            default -> looksLikeSentence(query) ? Mode.SENTENCE : Mode.WORD;
        };
        selectModeButton();
        input.setText(query);
        if (useNmt.isSelected())
        {
            onNmtToggled();
        }
        else
        {
            run();
        }
    }

    private Node buildToolbar()
    {
        HBox bar = new HBox(3);
        ToggleGroup group = new ToggleGroup();
        for (Mode m : Mode.values())
        {
            ToggleButton button = new ToggleButton(m.label, PixelIcons.large(m.icon));
            button.getStyleClass().add("tool");
            button.setContentDisplay(ContentDisplay.TOP);
            button.setToggleGroup(group);
            button.setSelected(m == mode);
            button.setOnAction(e ->
            {
                // Không cho bỏ chọn hết
                button.setSelected(true);
                switchMode(m);
            });
            modeButtons.put(m, button);
            bar.getChildren().add(button);
        }

        Button sources = new Button("Nguồn từ điển", PixelIcons.large("folder"));
        sources.getStyleClass().add("tool");
        sources.setContentDisplay(ContentDisplay.TOP);
        sources.setOnAction(e -> SourceDialog.show(input.getScene().getWindow(), ctx, this::run));
        bar.getChildren().add(sources);

        Button history = new Button("Lịch sử", PixelIcons.large("history"));
        history.getStyleClass().add("tool");
        history.setContentDisplay(ContentDisplay.TOP);
        history.setOnAction(e -> openHistory());
        bar.getChildren().add(history);

        // Chỉ hiện khi có cả thư viện lẫn mô hình. Bản đóng gói thường không kèm AI, lúc đó ô này
        // biến mất và app chạy như cũ.
        if (NmtSupport.isAvailable(ctx.dataDir()))
        {
            useNmt.getStyleClass().add("nmt-toggle");
            useNmt.setGraphic(PixelIcons.small("chip"));
            useNmt.setTooltip(new Tooltip("Chạy một mô hình dịch máy 98 MB ngay trên máy bạn. "
                    + "Không cần mạng, nhưng đây là AI chứ không phải tra từ điển."));
            useNmt.setOnAction(e -> onNmtToggled());
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            bar.getChildren().addAll(spacer, useNmt);
            useNmt.setMaxHeight(Double.MAX_VALUE);
        }
        return bar;
    }

    // Bật ô AI: nạp mô hình ở luồng nền rồi dịch lại
    private void onNmtToggled()
    {
        if (!useNmt.isSelected() || nmtEngine != null || nmtLoading)
        {
            run();
            return;
        }
        nmtLoading = true;
        status.setText("Đang nạp mô hình AI (98 MB) ...");
        Task<OnnxNmtEngine> task = new Task<>()
        {
            @Override
            protected OnnxNmtEngine call()
            {
                return NmtSupport.load(ctx.dataDir()).orElse(null);
            }
        };
        task.setOnSucceeded(e ->
        {
            nmtLoading = false;
            nmtEngine = task.getValue();
            if (nmtEngine == null)
            {
                useNmt.setSelected(false);
                status.setText("Không nạp được mô hình AI.");
            }
            else
            {
                mode = Mode.SENTENCE;
                selectModeButton();
                run();
            }
        });
        task.setOnFailed(e ->
        {
            nmtLoading = false;
            useNmt.setSelected(false);
            status.setText("Không nạp được mô hình AI: " + task.getException());
        });
        Thread thread = new Thread(task, "nmt-loader");
        thread.setDaemon(true);
        thread.start();
    }

    // Đồng bộ nút khi chế độ đổi từ code (bấm vào kết quả, hoặc -Ddict.mode)
    private void selectModeButton()
    {
        ToggleButton button = modeButtons.get(mode);
        if (button != null)
        {
            button.setSelected(true);
        }
    }

    private Node buildInput()
    {
        input.setPromptText("Nhập từ, cụm từ, cả câu tiếng Anh, hoặc nghĩa tiếng Việt rồi bấm Enter");
        input.getStyleClass().add("search-input");
        input.setOnKeyPressed(e ->
        {
            if (e.getCode() == KeyCode.ENTER)
            {
                search();
            }
            else if (e.getCode() == KeyCode.UP)
            {
                recall(1);
                e.consume();
            }
            else if (e.getCode() == KeyCode.DOWN)
            {
                recall(-1);
                e.consume();
            }
        });
        // Người dùng tự gõ hay dán thì thôi duyệt lịch sử
        input.textProperty().addListener((obs, old, text) ->
        {
            if (!recalling)
            {
                recallIndex = -1;
            }
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        return new HBox(input);
    }

    private Node buildResultArea()
    {
        // Bắt buộc để Label wrapText co theo chiều rộng
        resultScroll.setFitToWidth(true);
        resultScroll.getStyleClass().add("result-scroll");
        VBox.setVgrow(resultScroll, Priority.ALWAYS);
        return resultScroll;
    }

    // ------------------------------------------------------------------ tra cứu

    // Người dùng chủ động tra (Enter, bấm vào kết quả hay gợi ý): tra rồi ghi vào lịch sử. Đổi chế độ hay
    // chạy lại sau khi bật/tắt nguồn thì không ghi, kẻo cùng một câu bị ghi nhiều lần.
    private void search()
    {
        run();
        ctx.history().add(mode.name().toLowerCase(Locale.ROOT), input.getText());
        recallIndex = -1;
    }

    private void openHistory()
    {
        HistoryDialog.show(input.getScene().getWindow(), ctx.history()).ifPresent(entry ->
        {
            mode = switch (entry.mode())
            {
                case "sentence" -> Mode.SENTENCE;
                case "reverse" -> Mode.REVERSE;
                default -> Mode.WORD;
            };
            selectModeButton();
            input.setText(entry.query());
            search();
            input.requestFocus();
            input.positionCaret(input.getText().length());
        });
    }

    // direction = 1 là lùi về câu cũ hơn, -1 là tiến về câu mới hơn; tiến quá câu mới nhất thì trả lại phần đang gõ dở
    private void recall(int direction)
    {
        List<HistoryStore.Entry> entries = ctx.history().entries();
        if (entries.isEmpty())
        {
            return;
        }
        int next = recallIndex + direction;
        if (recallIndex == -1 && direction > 0)
        {
            recallDraft = input.getText() == null ? "" : input.getText();
            // Câu vừa tra xong đang nằm sẵn trong ô: bỏ qua nó để ↑ đi thẳng tới câu trước đó
            next = entries.size() > 1 && entries.get(0).query().equals(recallDraft.strip()) ? 1 : 0;
        }
        next = Math.max(-1, Math.min(next, entries.size() - 1));
        recallIndex = next;
        recalling = true;
        input.setText(next == -1 ? recallDraft : entries.get(next).query());
        input.positionCaret(input.getText().length());
        recalling = false;
    }

    private void run()
    {
        // Lượt dịch AI cũ còn đang chạy thì bỏ đi, kẻo kết quả cũ đè lên kết quả mới
        cancelNmtTask();
        String query = input.getText() == null ? "" : input.getText().trim();
        if (query.isEmpty())
        {
            showWelcome();
            return;
        }
        long t0 = System.nanoTime();
        Node content = switch (mode)
        {
            case WORD -> lookupWord(query);
            case SENTENCE -> translateSentence(query);
            case REVERSE -> searchVietnamese(query);
        };
        double ms = (System.nanoTime() - t0) / 1_000_000.0;

        resultHolder.getChildren().setAll(content);
        // Kết quả mới thì xem từ đầu
        resultScroll.setVvalue(0);
        status.setText(String.format(Locale.ROOT, "%s · %,d mục từ · %,d khoá · tra trong %.1f ms", mode.label,
                ctx.pack().entryCount(), ctx.pack().keyCount(), ms));
    }

    private Node lookupWord(String query)
    {
        List<Entry> entries = ctx.lookup().lookupAll(query);
        if (!entries.isEmpty())
        {
            String norm = TextNormalizer.normalizeHeadword(query);
            return ResultRenderer.renderEntries(entries, norm.contains(" ") ? norm : null);
        }

        // Trượt thì thử dạng nguyên thể trước (gave → give), rồi mới đoán từ gõ sai
        var resolved = ctx.lookup().resolve(query);
        if (resolved.isPresent())
        {
            VBox box = new VBox(6);
            box.getChildren().add(
                    ResultRenderer.message("Không có \"" + query + "\". Dạng nguyên thể: " + resolved.get().key()));
            box.getChildren().add(ResultRenderer.renderEntries(resolved.get().entries()));
            return box;
        }
        // Gõ hẳn một câu vào ô tra từ là chuyện thường gặp: dịch luôn và nói rõ đã làm gì
        if (looksLikeSentence(query))
        {
            VBox box = new VBox(6);
            box.getChildren().add(ResultRenderer
                    .message("\"" + query + "\" là một câu, không phải một từ — đã tự chuyển sang dịch câu."));
            box.getChildren().add(translateSentence(query));
            return box;
        }

        List<ReverseSearchService.Hit> near = ctx.search().fuzzyEnglish(query, 10);
        if (near.isEmpty())
        {
            return ResultRenderer.message("Không có \"" + query + "\" và không tìm được từ nào gần giống.");
        }
        VBox box = new VBox(6);
        box.getChildren().add(ResultRenderer.message("Không có \"" + query + "\". Có phải bạn muốn tìm:"));
        box.getChildren().add(ResultRenderer.renderHits(near, this::openWord));
        return box;
    }

    private Node translateSentence(String sentence)
    {
        // Dán tiếng Việt vào chế độ Anh → Việt thì ra chữ vô nghĩa: báo thẳng thay vì dịch bừa
        if (TextNormalizer.looksVietnamese(sentence))
        {
            return ResultRenderer.note("Đây có vẻ là tiếng Việt. Chế độ này dịch từ tiếng Anh sang tiếng Việt; muốn tìm "
                    + "từ tiếng Anh cho một nghĩa tiếng Việt thì chuyển sang chế độ Việt → Anh (Ctrl+3).");
        }
        // Mô hình AI hay bịa khi chỉ có một từ lẻ, thiếu ngữ cảnh: từ đơn thì dùng từ điển và bộ luật
        boolean singleWord = sentence.trim().split("\\s+").length < 2;
        if (useNmt.isSelected() && nmtEngine != null && !singleWord)
        {
            return translateWithNmt(sentence);
        }
        var engine = ctx.sentenceEngine();
        String translated = engine.translate(sentence).getFirst().displayGloss();
        List<Segment> segments = engine.glossSegments(sentence);

        VBox box = new VBox(10);
        box.getChildren().add(ResultRenderer.translation(translated));
        box.getChildren().add(
                ResultRenderer.note("Bản dịch trên do bộ luật ngữ pháp dựng ra: chọn nghĩa theo từ loại rồi sắp lại "
                        + "trật tự tiếng Việt. Câu càng phức tạp thì càng dễ sai — đối chiếu phần "
                        + "chú giải bên dưới, bấm ô có dấu ▾ để xem các nghĩa khác."));
        box.getChildren().add(ResultRenderer.renderGloss(segments));
        return box;
    }

    private Node searchVietnamese(String query)
    {
        List<ReverseSearchService.Hit> hits = ctx.search().searchVietnamese(query, 25);
        // Gõ sai chính tả tiếng Việt thì tìm theo âm tiết vẫn "chạy" nhưng ra rời rạc, nên
        // phải gợi ý từ đúng để người dùng biết.
        List<String> suggestions = ctx.search().suggestVietnamese(query, 3);

        VBox box = new VBox(6);
        if (!suggestions.isEmpty())
        {
            box.getChildren().add(ResultRenderer.suggestion(suggestions, this::searchAgain));
        }
        if (hits.isEmpty())
        {
            box.getChildren().add(ResultRenderer.message("Không tìm thấy từ tiếng Anh nào cho \"" + query + "\"."));
        }
        else
        {
            box.getChildren().add(ResultRenderer.renderHits(hits, this::openWord));
        }
        return box;
    }

    // Mỗi câu mất 150-450 ms, đủ để đứng hình nếu chạy trên luồng giao diện
    private Node translateWithNmt(String sentence)
    {
        VBox box = new VBox(10);
        box.getChildren().add(ResultRenderer.message("Đang dịch bằng mô hình AI ..."));

        // Đoạn nhiều câu dịch từng câu một để báo được tiến độ và huỷ được giữa chừng
        Task<String> task = new Task<>()
        {
            @Override
            protected String call()
            {
                List<String> sentences = SentenceSplitter.split(sentence);
                StringBuilder vi = new StringBuilder();
                for (int i = 0; i < sentences.size(); i++)
                {
                    if (isCancelled())
                    {
                        return null;
                    }
                    if (sentences.size() > 1)
                    {
                        updateMessage("Đang dịch bằng mô hình AI: câu " + (i + 1) + "/" + sentences.size());
                    }
                    if (!vi.isEmpty())
                    {
                        vi.append(' ');
                    }
                    vi.append(nmtEngine.translate(sentences.get(i)).getFirst().displayGloss());
                }
                return vi.toString();
            }
        };
        nmtTask = task;
        task.messageProperty().addListener((obs, old, message) ->
        {
            if (task == nmtTask && message != null && !message.isEmpty())
            {
                status.setText(message);
            }
        });
        long t0 = System.nanoTime();
        task.setOnSucceeded(e ->
        {
            if (task != nmtTask)
            {
                return;
            }
            VBox done = new VBox(10);
            done.getChildren().add(ResultRenderer.translation(task.getValue()));
            done.getChildren().add(
                    ResultRenderer.note("Bản dịch này do một mô hình AI (opus-mt-en-vi, 98 MB) chạy ngay trên máy bạn "
                            + "tạo ra — không qua mạng, nhưng đây là AI chứ không phải tra từ điển. "
                            + "Bỏ tick ở góc trên để quay về bản dịch bằng luật."));
            done.getChildren().add(ResultRenderer.renderGloss(ctx.sentenceEngine().glossSegments(sentence)));
            box.getChildren().setAll(done);
            status.setText(String.format(Locale.ROOT, "Mô hình AI trên máy · %,d mục từ · dịch trong %.0f ms",
                    ctx.pack().entryCount(), (System.nanoTime() - t0) / 1_000_000.0));
        });
        task.setOnFailed(e ->
        {
            if (task == nmtTask)
            {
                box.getChildren().setAll(ResultRenderer.message("Lỗi khi chạy mô hình: " + task.getException()));
            }
        });
        Thread thread = new Thread(task, "nmt-translate");
        thread.setDaemon(true);
        thread.start();
        return box;
    }

    private void cancelNmtTask()
    {
        if (nmtTask != null)
        {
            nmtTask.cancel();
            nmtTask = null;
        }
    }

    // Bấm vào gợi ý chính tả thì tra lại bằng từ được gợi ý
    private void searchAgain(String query)
    {
        input.setText(query);
        search();
    }

    private void switchMode(Mode target)
    {
        mode = target;
        selectModeButton();
        run();
    }

    // Bấm vào một kết quả thì mở hẳn mục từ đó ở chế độ tra từ
    private void openWord(String headword)
    {
        mode = Mode.WORD;
        selectModeButton();
        input.setText(headword);
        search();
    }

    // Từ bốn từ trở lên thì coi là câu, không phải mục từ cần tra
    private static boolean looksLikeSentence(String query)
    {
        return query != null && query.trim().split("\\s+").length >= 4;
    }

    private void showWelcome()
    {
        VBox box = new VBox(6);
        box.getChildren().add(ResultRenderer.message("""
                DictPocket: từ điển Anh - Việt dùng offline. Không cần mạng, không có tài khoản.

                Thử:
                  · Tra từ     :  about,  give up,  acid-proof
                  · Dịch câu   :  He gave up his job because the system could not keep up
                  · Việt → Anh :  chăm sóc   (gõ không dấu "cham soc" cũng ra cùng kết quả)

                Phím tắt: Ctrl+1 / Ctrl+2 / Ctrl+3 đổi chế độ, Ctrl+L về ô nhập, Ctrl+H xem lịch sử tra, phím ↑ ↓ gọi lại câu đã tra.
                """));
        resultHolder.getChildren().setAll(box);
        status.setText(String.format(Locale.ROOT,
                "%s · %,d mục từ · %,d khoá tra cứu · nạp dữ liệu trong %d ms · dữ liệu: %s", AppVersion.display(),
                ctx.pack().entryCount(), ctx.pack().keyCount(), ctx.startupMillis(), ctx.dataDir().toAbsolutePath()));
    }
}
