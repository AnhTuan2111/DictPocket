package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.core.source.DictSource;
import com.anhtuan.dict.core.source.SourceCatalog;
import com.anhtuan.dict.desktop.config.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;

// Quản lý nguồn từ điển: bật / tắt / đổi thứ tự ưu tiên. Có hiệu lực ngay, không phải sinh lại
// dữ liệu vì mỗi mục từ trong pack mang sẵn sourceId, tắt một nguồn chỉ là một phép lọc.
// Thêm nguồn mới thì phải chạy lại lệnh build, vì pack là file bất biến.
final class SourceDialog
{
    private SourceDialog()
    {
    }

    static void show(Window owner, AppContext ctx, Runnable onChanged)
    {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);

        VBox list = new VBox(6);
        render(list, ctx, onChanged);

        Label note = new Label("""
                Bật/tắt có hiệu lực ngay. Nguồn ở trên được ưu tiên: nghĩa của nó hiện trước.

                Thêm nguồn mới (một file .tsv hai cột: từ tiếng Anh <TAB> nghĩa tiếng Việt):
                  ImporterMain build anhviet109K.txt data/build your-dictionary.tsv
                """);
        note.getStyleClass().add("message");
        note.setWrapText(true);

        Button close = new Button("Đóng");
        close.getStyleClass().add("dialog-button");
        close.setOnAction(e -> stage.close());
        HBox bottom = new HBox(close);
        bottom.setAlignment(Pos.CENTER_RIGHT);

        VBox content = new VBox(10, list, note, bottom);
        content.setPadding(new Insets(12));
        WindowChrome.install(stage, PixelIcons.small("folder"), "Nguồn từ điển", content, 540, 380, false);
        stage.showAndWait();
    }

    private static void render(VBox list, AppContext ctx, Runnable onChanged)
    {
        list.getChildren().clear();
        SourceCatalog catalog = ctx.catalog();

        for (DictSource source : catalog.all())
        {
            HBox row = new HBox(8);
            row.getStyleClass().add("source-row");

            CheckBox enabled = new CheckBox(source.name());
            enabled.setSelected(source.enabled());
            enabled.setOnAction(e ->
            {
                ctx.updateCatalog(ctx.catalog().setEnabled(source.id(), enabled.isSelected()));
                render(list, ctx, onChanged);
                onChanged.run();
            });

            Label count = new Label(String.format(Locale.ROOT, "%,d mục · %s", source.entries(), source.format()));
            count.getStyleClass().add("source-count");

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            row.getChildren().addAll(enabled, count, spacer, moveButton("▲", -1, source, ctx, list, onChanged),
                    moveButton("▼", 1, source, ctx, list, onChanged));
            list.getChildren().add(row);
        }

        if (!catalog.hasEnabled())
        {
            Label warning = new Label("Đã tắt hết nguồn — ứng dụng đang dùng lại tất cả để màn hình không trống trơn.",
                    PixelIcons.large("warn"));
            warning.getStyleClass().add("source-warning");
            warning.setWrapText(true);
            list.getChildren().add(warning);
        }
    }

    private static Button moveButton(String text, int delta, DictSource source, AppContext ctx, VBox list,
            Runnable onChanged)
    {
        Button button = new Button(text);
        button.getStyleClass().add("source-move");
        button.setOnAction(e ->
        {
            ctx.updateCatalog(ctx.catalog().move(source.id(), delta));
            render(list, ctx, onChanged);
            onChanged.run();
        });
        return button;
    }
}
