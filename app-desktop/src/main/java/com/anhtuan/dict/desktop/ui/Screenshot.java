package com.anhtuan.dict.desktop.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

// Chụp cửa sổ ra PNG rồi thoát, bật bằng -Ddict.screenshot=<file>.
// Chụp từ scene nên không dính khung hệ điều hành, dùng để làm ảnh cho tài liệu.
final class Screenshot
{
    private Screenshot()
    {
    }

    static void scheduleIfRequested(Scene scene)
    {
        String target = System.getProperty("dict.screenshot");
        if (target == null || target.isBlank())
        {
            return;
        }
        // Chờ giao diện dựng xong và con trỏ nhấp nháy tắt
        PauseTransition wait = new PauseTransition(Duration.millis(900));
        wait.setOnFinished(e ->
        {
            try
            {
                save(scene, Path.of(target));
            }
            catch (IOException ex)
            {
                System.err.println("Cannot save screenshot: " + ex.getMessage());
            }
            Platform.exit();
        });
        wait.play();
    }

    private static void save(Scene scene, Path file) throws IOException
    {
        WritableImage image = scene.getRoot().snapshot(null, null);
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, width, height, argb, 0, width);
        ImageIO.write(out, "png", file.toFile());
    }
}
