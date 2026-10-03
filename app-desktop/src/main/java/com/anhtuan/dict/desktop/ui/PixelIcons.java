package com.anhtuan.dict.desktop.ui;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.HashMap;
import java.util.Map;

// Icon pixel 16x16 trong resources/ui, phóng nguyên số lần khi hiển thị
final class PixelIcons
{
    private static final Map<String, Image> CACHE = new HashMap<>();

    private PixelIcons()
    {
    }

    static ImageView small(String name)
    {
        return view(name, 16);
    }

    static ImageView large(String name)
    {
        return view(name, 32);
    }

    private static ImageView view(String name, int size)
    {
        Image image = CACHE.computeIfAbsent(name,
                n -> new Image(PixelIcons.class.getResource("/ui/" + n + ".png").toExternalForm()));
        ImageView view = new ImageView(image);
        view.setFitWidth(size);
        view.setFitHeight(size);
        // Không làm mịn, để giữ nét pixel khi phóng
        view.setSmooth(false);
        return view;
    }
}
