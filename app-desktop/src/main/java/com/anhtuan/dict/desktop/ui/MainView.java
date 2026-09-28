package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Segment;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.service.ReverseSearchService;
import com.anhtuan.dict.desktop.config.AppContext;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.concurrent.Task;
import com.anhtuan.dict.desktop.config.AppVersion;
import com.anhtuan.dict.desktop.config.NmtSupport;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Locale;

/**
 * Cua so chinh. Ba che do dung chung mot o nhap:
 * <ul>
 *   <li>TRA TU  - Anh sang Viet, mot tu hoac cum (F1); truot thi goi y tu gan giong (F4)</li>
 *   <li>DICH CAU - chu giai theo cum (F2)</li>
 *   <li>VIET-ANH - tim tu tieng Anh tu nghia tieng Viet, xep theo do lien quan (F3)</li>
 * </ul>
 *
 * <p>Toan bo tra cuu chay tren UI thread va day la CO Y: do do duoc la 13,7 us moi luot
 * tra va 16 ms cho search - dua sang thread khac chi them phuc tap ma khong ai thay nhanh
 * hon. Neu sau nay cam NMT vao (moi cau vai tram ms) thi luc do moi can Task nen.
 */
public final class MainView {

    private enum Mode {
        WORD("Tra từ (Anh → Việt)"),
        SENTENCE("Dịch câu (Anh → Việt)"),
        REVERSE("Việt → Anh");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private final AppContext ctx;
    private final TextField input = new TextField();
    private final VBox resultHolder = new VBox();
    private final ScrollPane resultScroll = new ScrollPane(resultHolder);
    private final Label status = new Label();
    private final java.util.Map<Mode, ToggleButton> modeButtons = new java.util.EnumMap<>(Mode.class);
    private final CheckBox useNmt = new CheckBox("Dùng mô hình AI trên máy");
    private Mode mode = Mode.WORD;

    /** Engine NMT, nap lan dau khi nguoi dung bat. null = chua nap. */
    private com.anhtuan.dict.nmt.OnnxNmtEngine nmtEngine;
    private boolean nmtLoading;

    /** Truy van mo san luc khoi dong, lay tu tham so dong lenh. Co the rong. */
    private final String initialQuery;

    public MainView(AppContext ctx, List<String> args) {
        this.ctx = ctx;
        this.initialQuery = args == null || args.isEmpty() ? null : String.join(" ", args);
    }

    public void show(Stage stage) {
        VBox root = new VBox(8);
        root.setPadding(new Insets(12));
        root.getChildren().addAll(buildToolbar(), buildInput(), buildResultArea(), status);
        status.getStyleClass().add("status");

        Scene scene = new Scene(root, 900, 640);
        scene.getStylesheets().add(
                MainView.class.getResource("/css/dict.css").toExternalForm());

        // Ctrl+1/2/3 doi che do, Ctrl+L ve o nhap: nguoi tra tu lien tuc khong muon roi ban phim.
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.WORD));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT2, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.SENTENCE));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.DIGIT3, KeyCombination.CONTROL_DOWN),
                () -> switchMode(Mode.REVERSE));
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.L, KeyCombination.CONTROL_DOWN),
                () -> { input.requestFocus(); input.selectAll(); });

        stage.setTitle("Từ điển offline Anh - Việt  " + AppVersion.display());
        AppIcon.applyTo(stage);
        stage.setScene(scene);
        stage.show();
        input.requestFocus();

        showWelcome();
        applyStartupQuery();
    }

    /**
     * Cho phep mo san mot truy van: {@code -Ddict.query="give up" -Ddict.mode=word}.
     *
     * <p>Hai cong dung that: chup anh man hinh de dan vao tai lieu ma khong phai go tay,
     * va kiem thu tay nhanh.
     */
    private void applyStartupQuery() {
        // -Ddict.nmt=true bat san mo hinh AI (dung khi chup anh va kiem thu tay)
        if (Boolean.getBoolean("dict.nmt") && NmtSupport.isAvailable(ctx.dataDir())) {
            useNmt.setSelected(true);
        }
        // Uu tien tham so dong lenh:  TuDienOffline.exe "give up"
        String query = initialQuery != null ? initialQuery : System.getProperty("dict.query");
        if (query == null || query.isBlank()) return;
        String m = System.getProperty("dict.mode", "").toLowerCase(Locale.ROOT);
        mode = switch (m) {
            case "sentence" -> Mode.SENTENCE;
            case "reverse" -> Mode.REVERSE;
            case "word" -> Mode.WORD;
            default -> looksLikeSentence(query) ? Mode.SENTENCE : Mode.WORD;
        };
        selectModeButton();
        input.setText(query);
        if (useNmt.isSelected()) onNmtToggled(); else run();
    }

    private Node buildToolbar() {
        HBox bar = new HBox(6);
        ToggleGroup group = new ToggleGroup();
        for (Mode m : Mode.values()) {
            ToggleButton b = new ToggleButton(m.label);
            b.setToggleGroup(group);
            b.setSelected(m == mode);
            b.setOnAction(e -> {
                b.setSelected(true);                 // khong cho bo chon het
                switchMode(m);
            });
            modeButtons.put(m, b);
            bar.getChildren().add(b);
        }

        Button sources = new Button("Nguồn từ điển");
        sources.getStyleClass().add("source-button");
        sources.setOnAction(e -> SourceDialog.show(
                input.getScene().getWindow(), ctx, this::run));
        bar.getChildren().add(sources);

        // Chi hien khi ca thu vien lan mo hinh deu co. Ban dong goi mac dinh khong kem
        // mo hinh AI, luc do o nay bien mat va app chay y nhu cu.
        if (NmtSupport.isAvailable(ctx.dataDir())) {
            useNmt.getStyleClass().add("nmt-toggle");
            useNmt.setTooltip(new javafx.scene.control.Tooltip(
                    "Chạy một mô hình dịch máy 98 MB ngay trên máy bạn. Không cần mạng, "
                            + "nhưng đây là AI chứ không phải tra từ điển."));
            useNmt.setOnAction(e -> onNmtToggled());
            bar.getChildren().add(new javafx.scene.layout.Region() {{
                javafx.scene.layout.HBox.setHgrow(this, Priority.ALWAYS);
            }});
            bar.getChildren().add(useNmt);
        }
        return bar;
    }

    /** Bat o "dung AI": nap mo hinh o luong nen roi dich lai. */
    private void onNmtToggled() {
        if (!useNmt.isSelected() || nmtEngine != null || nmtLoading) {
            run();
            return;
        }
        nmtLoading = true;
        status.setText("Đang nạp mô hình AI (98 MB) ...");
        Task<com.anhtuan.dict.nmt.OnnxNmtEngine> task = new Task<>() {
            @Override
            protected com.anhtuan.dict.nmt.OnnxNmtEngine call() {
                return NmtSupport.load(ctx.dataDir()).orElse(null);
            }
        };
        task.setOnSucceeded(e -> {
            nmtLoading = false;
            nmtEngine = task.getValue();
            if (nmtEngine == null) {
                useNmt.setSelected(false);
                status.setText("Không nạp được mô hình AI.");
            } else {
                mode = Mode.SENTENCE;
                selectModeButton();
                run();
            }
        });
        task.setOnFailed(e -> {
            nmtLoading = false;
            useNmt.setSelected(false);
            status.setText("Không nạp được mô hình AI: " + task.getException());
        });
        Thread thread = new Thread(task, "nap-mo-hinh-nmt");
        thread.setDaemon(true);
        thread.start();
    }

    /** Dong bo nut khi che do bi doi tu code (bam vao ket qua, hoac -Ddict.mode). */
    private void selectModeButton() {
        ToggleButton b = modeButtons.get(mode);
        if (b != null) b.setSelected(true);
    }

    private Node buildInput() {
        input.setPromptText("Nhập từ, cụm từ, cả câu tiếng Anh, hoặc nghĩa tiếng Việt rồi bấm Enter");
        input.getStyleClass().add("search-input");
        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) run();
        });
        HBox.setHgrow(input, Priority.ALWAYS);
        return new HBox(input);
    }

    private Node buildResultArea() {
        resultScroll.setFitToWidth(true);            // bat buoc de Label wrapText hoat dong
        resultScroll.getStyleClass().add("result-scroll");
        VBox.setVgrow(resultScroll, Priority.ALWAYS);
        return resultScroll;
    }

    // ------------------------------------------------------------------ tra cuu

    private void run() {
        String query = input.getText() == null ? "" : input.getText().trim();
        if (query.isEmpty()) {
            showWelcome();
            return;
        }
        long t0 = System.nanoTime();
        Node content = switch (mode) {
            case WORD -> lookupWord(query);
            case SENTENCE -> translateSentence(query);
            case REVERSE -> searchVietnamese(query);
        };
        double ms = (System.nanoTime() - t0) / 1_000_000.0;

        resultHolder.getChildren().setAll(content);
        resultScroll.setVvalue(0);                   // ket qua moi thi phai xem tu dau
        status.setText(String.format(Locale.ROOT, "%s · %,d mục từ · %,d khoá · tra trong %.1f ms",
                mode.label, ctx.pack().entryCount(), ctx.pack().keyCount(), ms));
    }

    private Node lookupWord(String query) {
        List<Entry> entries = ctx.lookup().lookupAll(query);
        if (!entries.isEmpty()) {
            String norm = TextNormalizer.normalizeHeadword(query);
            return ResultRenderer.renderEntries(entries, norm.contains(" ") ? norm : null);
        }

        // Truot: thu lemma truoc (gave -> give), roi moi den doan tu go sai (F4).
        var resolved = ctx.lookup().resolve(query);
        if (resolved.isPresent()) {
            VBox box = new VBox(6);
            box.getChildren().add(ResultRenderer.message(
                    "Không có \"" + query + "\". Dạng nguyên thể: " + resolved.get().key()));
            box.getChildren().add(ResultRenderer.renderEntries(resolved.get().entries()));
            return box;
        }
        // Go han mot cau vao o tra tu la chuyen thuong gap. Bao "khong co tu nay" thi dung
        // ky thuat nhung vo dung; dich luon cau do roi noi ro minh vua lam gi thi tot hon.
        if (looksLikeSentence(query)) {
            VBox box = new VBox(6);
            box.getChildren().add(ResultRenderer.message(
                    "\"" + query + "\" là một câu, không phải một từ — đã tự chuyển sang dịch câu."));
            box.getChildren().add(translateSentence(query));
            return box;
        }

        List<ReverseSearchService.Hit> near = ctx.search().fuzzyEnglish(query, 10);
        if (near.isEmpty()) {
            return ResultRenderer.message("Không có \"" + query + "\" và không tìm được từ nào gần giống.");
        }
        VBox box = new VBox(6);
        box.getChildren().add(ResultRenderer.message(
                "Không có \"" + query + "\". Có phải bạn muốn tìm:"));
        box.getChildren().add(ResultRenderer.renderHits(near, this::openWord));
        return box;
    }

    private Node translateSentence(String sentence) {
        if (useNmt.isSelected() && nmtEngine != null) return translateWithNmt(sentence);
        var engine = ctx.sentenceEngine();
        String translated = engine.translate(sentence).getFirst().displayGloss();
        List<Segment> segments = engine.glossSegments(sentence);

        VBox box = new VBox(10);
        box.getChildren().add(ResultRenderer.translation(translated));
        box.getChildren().add(ResultRenderer.message(
                "Bản dịch trên do bộ luật ngữ pháp dựng ra: chọn nghĩa theo từ loại rồi sắp lại "
                        + "trật tự tiếng Việt. Câu càng phức tạp thì càng dễ sai — đối chiếu phần "
                        + "chú giải bên dưới, bấm ô có dấu ▾ để xem các nghĩa khác."));
        box.getChildren().add(ResultRenderer.renderGloss(segments));
        return box;
    }

    private Node searchVietnamese(String query) {
        List<ReverseSearchService.Hit> hits = ctx.search().searchVietnamese(query, 25);
        // Go sai chinh ta tieng Viet thi tim theo am tiet van "chay" nhung ra rac:
        // "cham sok" tung tra ve slow, sculp, shock. Phai noi cho nguoi dung biet.
        List<String> suggestions = ctx.search().suggestVietnamese(query, 3);

        VBox box = new VBox(6);
        if (!suggestions.isEmpty()) {
            box.getChildren().add(ResultRenderer.suggestion(suggestions, this::searchAgain));
        }
        if (hits.isEmpty()) {
            box.getChildren().add(ResultRenderer.message(
                    "Không tìm thấy từ tiếng Anh nào cho \"" + query + "\"."));
        } else {
            box.getChildren().add(ResultRenderer.renderHits(hits, this::openWord));
        }
        return box;
    }

    /**
     * Dich bang mo hinh no-ron. Chay o luong nen: moi cau ton 150-450 ms, du de cua so
     * dung hinh neu lam ngay tren luong giao dien.
     */
    private Node translateWithNmt(String sentence) {
        VBox box = new VBox(10);
        box.getChildren().add(ResultRenderer.message("Đang dịch bằng mô hình AI ..."));

        Task<String> task = new Task<>() {
            @Override
            protected String call() {
                return nmtEngine.translate(sentence).getFirst().displayGloss();
            }
        };
        long t0 = System.nanoTime();
        task.setOnSucceeded(e -> {
            VBox done = new VBox(10);
            done.getChildren().add(ResultRenderer.translation(task.getValue()));
            done.getChildren().add(ResultRenderer.message(
                    "Bản dịch này do một mô hình AI (opus-mt-en-vi, 98 MB) chạy ngay trên máy bạn "
                            + "tạo ra — không qua mạng, nhưng đây là AI chứ không phải tra từ điển. "
                            + "Bỏ tick ở góc trên để quay về bản dịch bằng luật."));
            done.getChildren().add(ResultRenderer.renderGloss(
                    ctx.sentenceEngine().glossSegments(sentence)));
            box.getChildren().setAll(done);
            status.setText(String.format(Locale.ROOT,
                    "Mô hình AI trên máy · %,d mục từ · dịch trong %.0f ms",
                    ctx.pack().entryCount(), (System.nanoTime() - t0) / 1_000_000.0));
        });
        task.setOnFailed(e -> box.getChildren().setAll(
                ResultRenderer.message("Lỗi khi chạy mô hình: " + task.getException())));
        Thread thread = new Thread(task, "dich-nmt");
        thread.setDaemon(true);
        thread.start();
        return box;
    }

    /** Bam vao goi y chinh ta -> tra lai bang tu duoc goi y. */
    private void searchAgain(String query) {
        input.setText(query);
        run();
    }

    private void switchMode(Mode target) {
        mode = target;
        selectModeButton();
        run();
    }

    /** Bam vao mot ket qua -> mo han muc tu do o che do tra tu. */
    private void openWord(String headword) {
        mode = Mode.WORD;
        selectModeButton();
        input.setText(headword);
        run();
    }

    /** Tu bon tu tro len thi coi la cau, khong phai muc tu can tra. */
    private static boolean looksLikeSentence(String query) {
        return query != null && query.trim().split("\s+").length >= 4;
    }

    private void showWelcome() {
        VBox box = new VBox(6);
        box.getChildren().add(ResultRenderer.message("""
                Từ điển offline Anh - Việt. Không cần mạng, không có tài khoản.

                Thử:
                  · Tra từ     :  about,  give up,  acid-proof
                  · Dịch câu   :  He gave up his job because the system could not keep up
                  · Việt → Anh :  chăm sóc   (gõ không dấu "cham soc" cũng ra cùng kết quả)

                Phím tắt: Ctrl+1 / Ctrl+2 / Ctrl+3 đổi chế độ, Ctrl+L về ô nhập.
                """));
        resultHolder.getChildren().setAll(box);
        status.setText(String.format(Locale.ROOT,
                "%s · %,d mục từ · %,d khoá tra cứu · nạp dữ liệu trong %d ms · dữ liệu: %s",
                AppVersion.display(), ctx.pack().entryCount(), ctx.pack().keyCount(),
                ctx.startupMillis(), ctx.dataDir().toAbsolutePath()));
    }
}
