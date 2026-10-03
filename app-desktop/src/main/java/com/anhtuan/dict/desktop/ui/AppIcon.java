package com.anhtuan.dict.desktop.ui;

import com.anhtuan.dict.desktop.config.AppVersion;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.Stage;

import java.io.InputStream;

// Icon của cửa sổ (taskbar, Alt+Tab). Icon của bộ cài thì jpackage nhúng vào file .exe.
public final class AppIcon
{
    // Mỗi cỡ là một ảnh vẽ riêng, không thu nhỏ từ ảnh lớn, để 16 px vẫn nét
    private static final int[] SIZES = {16, 32, 48, 256};

    private AppIcon()
    {
    }

    public static void applyTo(Stage stage)
    {
        for (int size : SIZES)
        {
            Image image = load(size);
            if (image != null)
            {
                stage.getIcons().add(image);
            }
        }
    }

    // Icon nhỏ cho thanh tiêu đề tự vẽ
    static ImageView titleIcon()
    {
        Image image = load(16);
        if (image == null)
        {
            return null;
        }
        ImageView view = new ImageView(image);
        view.setSmooth(false);
        return view;
    }

    private static Image load(int size)
    {
        String prefix = AppVersion.isAiEdition() ? "app-ai" : "app";
        try (InputStream in = AppIcon.class.getResourceAsStream("/icon/" + prefix + "-" + size + ".png"))
        {
            return in == null ? null : new Image(in);
        }
        catch (Exception e)
        {
            // Thiếu icon thì dùng icon mặc định, không phải lý do để app không chạy
            return null;
        }
    }
}
