package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.desktop.userdata.HistoryStore;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

// Hộp thoại lịch sử tra: lọc, chọn một mục để tra lại, xoá từng mục hoặc xoá hết.
// Chọn xong (bấm "Tra lại", bấm đúp hay Enter) thì đóng và trả mục đó về cho cửa sổ chính.
final class HistoryDialog
{
    private static final DateTimeFormatter THIS_YEAR = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.ROOT);
    private static final DateTimeFormatter OTHER_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ROOT);
    private static final int PREVIEW_CHARS = 110;

    private HistoryDialog()
    {
    }

    static Optional<HistoryStore.Entry> show(Window owner, HistoryStore store)
    {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);

        TextField filter = new TextField();
        filter.setPromptText("Gõ để lọc lịch sử");
        filter.getStyleClass().add("search-input");

        ObservableList<HistoryStore.Entry> items = FXCollections.observableArrayList(store.entries());
        FilteredList<HistoryStore.Entry> shown = new FilteredList<>(items, e -> true);
        filter.textProperty().addListener((obs, old, text) -> shown.setPredicate(e -> matches(e, text)));

        ListView<HistoryStore.Entry> list = new ListView<>(shown);
        list.getStyleClass().add("history-list");
        list.setPlaceholder(new Label(items.isEmpty() ? "Chưa có lịch sử tra." : "Không có mục nào khớp."));
        list.setCellFactory(v -> new ListCell<>()
        {
            @Override
            protected void updateItem(HistoryStore.Entry entry, boolean empty)
            {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : describe(entry));
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        AtomicReference<HistoryStore.Entry> picked = new AtomicReference<>();
        Runnable reopen = () ->
        {
            HistoryStore.Entry selected = list.getSelectionModel().getSelectedItem();
            if (selected != null)
            {
                picked.set(selected);
                stage.close();
            }
        };
        list.setOnMouseClicked(e ->
        {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2)
            {
                reopen.run();
            }
        });

        Button reopenButton = new Button("Tra lại");
        reopenButton.getStyleClass().add("dialog-button");
        reopenButton.setOnAction(e -> reopen.run());
        reopenButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        Runnable deleteSelected = () ->
        {
            HistoryStore.Entry selected = list.getSelectionModel().getSelectedItem();
            if (selected != null)
            {
                store.remove(selected);
                items.remove(selected);
            }
        };
        Button deleteButton = new Button("Xoá mục");
        deleteButton.getStyleClass().add("dialog-button");
        deleteButton.setOnAction(e -> deleteSelected.run());
        deleteButton.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());

        list.setOnKeyPressed(e ->
        {
            if (e.getCode() == KeyCode.ENTER)
            {
                reopen.run();
            }
            else if (e.getCode() == KeyCode.DELETE)
            {
                deleteSelected.run();
            }
        });
        // Enter ở ô lọc chọn luôn mục đầu tiên đang hiện
        filter.setOnKeyPressed(e ->
        {
            if (e.getCode() == KeyCode.ENTER && !shown.isEmpty())
            {
                picked.set(shown.getFirst());
                stage.close();
            }
            else if (e.getCode() == KeyCode.DOWN && !shown.isEmpty())
            {
                list.requestFocus();
                list.getSelectionModel().selectFirst();
            }
        });

        // Xoá hết cần bấm hai lần: lần đầu đổi chữ nút, không bấm tiếp trong 3 giây thì trở lại như cũ
        Button clearButton = new Button("Xoá hết");
        clearButton.getStyleClass().add("dialog-button");
        clearButton.setDisable(items.isEmpty());
        PauseTransition disarm = new PauseTransition(Duration.seconds(3));
        disarm.setOnFinished(e -> clearButton.setText("Xoá hết"));
        clearButton.setOnAction(e ->
        {
            if ("Xoá hết".equals(clearButton.getText()))
            {
                clearButton.setText("Chắc chắn?");
                disarm.playFromStart();
                return;
            }
            disarm.stop();
            store.clear();
            items.clear();
            clearButton.setText("Xoá hết");
            clearButton.setDisable(true);
            list.setPlaceholder(new Label("Chưa có lịch sử tra."));
        });

        Button close = new Button("Đóng");
        close.getStyleClass().add("dialog-button");
        close.setOnAction(e -> stage.close());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, reopenButton, deleteButton, clearButton, spacer, close);
        buttons.setAlignment(Pos.CENTER_LEFT);

        Label note = new Label("Lịch sử lưu trên máy này, không gửi đi đâu. Bấm đúp hoặc Enter để tra lại.");
        note.getStyleClass().add("message");
        note.setWrapText(true);

        VBox content = new VBox(8, filter, list, note, buttons);
        content.setPadding(new Insets(12));
        WindowChrome.install(stage, PixelIcons.small("history"), "Lịch sử tra", content, 600, 460, false);
        stage.showAndWait();
        return Optional.ofNullable(picked.get());
    }

    private static boolean matches(HistoryStore.Entry entry, String text)
    {
        return text == null || text.isBlank() || entry.query().toLowerCase(Locale.ROOT).contains(text.strip().toLowerCase(Locale.ROOT));
    }

    // "04/10 14:32 Tra từ give up"
    private static String describe(HistoryStore.Entry entry)
    {
        ZonedDateTime time = Instant.ofEpochSecond(entry.epochSecond()).atZone(ZoneId.systemDefault());
        DateTimeFormatter format = time.toLocalDate().getYear() == LocalDate.now().getYear() ? THIS_YEAR : OTHER_YEAR;
        String query = entry.query().length() > PREVIEW_CHARS ? entry.query().substring(0, PREVIEW_CHARS) + "…" : entry.query();
        return format.format(time) + "   " + label(entry.mode()) + "   " + query;
    }

    // Tên chế độ ngắn gọn cho một dòng trong danh sách
    static String label(String mode)
    {
        return switch (mode)
        {
            case "word" -> "Tra từ";
            case "sentence" -> "Dịch câu";
            case "reverse" -> "Việt → Anh";
            default -> mode;
        };
    }
}
