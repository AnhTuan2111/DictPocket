package com.anhtuan.dict.desktop;

import com.anhtuan.dict.desktop.config.AppContext;
import com.anhtuan.dict.desktop.ui.AppIcon;
import com.anhtuan.dict.desktop.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Diem vao cua app desktop.
 *
 * <p>Vong doi cua JavaFX duoc dung dung nhu no duoc thiet ke:
 * <ul>
 *   <li>{@link #init()} chay TRUOC khi UI hien - nap du lieu o day thi cua so khong
 *       bi hien ra roi treo mot luc;</li>
 *   <li>{@link #start(Stage)} chi con viec ve, chay tren JavaFX Application Thread;</li>
 *   <li>{@link #stop()} dong {@link AppContext} de unmap cac file mmap ngay lap tuc.</li>
 * </ul>
 *
 * <p>Khong co Spring Boot o day - ly do da ghi trong {@link AppContext}.
 */
public final class DictApp extends Application {

    private AppContext ctx;
    private String failure;

    @Override
    public void init() {
        try {
            ctx = new AppContext();
        } catch (RuntimeException e) {
            // Thieu du lieu la loi thuong gap nhat khi chay lan dau. Bao ngay tren cua so,
            // dung de nguoi dung phai di doc stack trace trong console.
            failure = e.getMessage();
        }
    }

    @Override
    public void start(Stage stage) {
        if (ctx == null) {
            Label label = new Label(failure);
            label.setWrapText(true);
            label.setStyle("-fx-padding: 16; -fx-font-family: 'Consolas', monospace;");
            stage.setScene(new Scene(new VBox(label), 720, 260));
            stage.setTitle("Tu dien offline - thieu du lieu");
            AppIcon.applyTo(stage);
            stage.show();
            return;
        }
        new MainView(ctx, getParameters().getRaw()).show(stage);
    }

    @Override
    public void stop() {
        if (ctx != null) ctx.close();
        Platform.exit();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
