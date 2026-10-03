package com.anhtuan.dict.desktop.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

// Màn báo lỗi khi không tìm thấy dữ liệu từ điển
public final class MissingDataView
{
    private MissingDataView()
    {
    }

    public static void show(Stage stage, String details)
    {
        Label headline = new Label("Không tìm thấy dữ liệu từ điển. Hãy cài lại DictPocket.");
        headline.getStyleClass().add("headline-text");
        Label technical = new Label(details);
        technical.getStyleClass().add("technical");
        technical.setWrapText(true);

        Button close = new Button("Đóng");
        close.getStyleClass().add("dialog-button");
        close.setOnAction(e -> stage.close());

        VBox text = new VBox(8, headline, technical, close);
        HBox content = new HBox(14, PixelIcons.large("warn"), text);
        content.setPadding(new Insets(14));
        WindowChrome.install(stage, AppIcon.titleIcon(), "DictPocket", content, 720, 260, false);
        stage.show();
    }
}
