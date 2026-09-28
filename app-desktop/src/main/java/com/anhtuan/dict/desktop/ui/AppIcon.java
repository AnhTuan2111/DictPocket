package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.desktop.config.AppVersion;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.InputStream;

/**
 * Icon cua cua so - thanh tieu de, thanh taskbar va Alt+Tab.
 *
 * <p>Nap nhieu co mot luc chu khong nap moi ban 256: JavaFX tu chon co gan nhat roi thu nho,
 * ma thu nho 256 xuong 16 thi nhoe. Ban ve san cho tung co ro hon han - xem
 * {@code scripts/tao-icon.py}.
 *
 * <p>Icon cua BO CAI thi khac, do {@code jpackage --icon} nhung vao file .exe luc dong goi;
 * cho nay chi lo phan cua so luc dang chay.
 */
public final class AppIcon {

    /** Co nao cung ve rieng, khong phai thu nho tu mot ban. */
    private static final int[] SIZES = {16, 32, 48, 256};

    private AppIcon() {}

    public static void applyTo(Stage stage) {
        String prefix = AppVersion.isAiEdition() ? "app-ai" : "app";
        for (int size : SIZES) {
            try (InputStream in = AppIcon.class.getResourceAsStream(
                    "/icon/" + prefix + "-" + size + ".png")) {
                if (in != null) stage.getIcons().add(new Image(in));
            } catch (Exception e) {
                // Thieu icon khong phai ly do de app khong chay. Cua so se dung icon mac dinh.
            }
        }
    }
}
