package com.anhtuan.dict.desktop;

import javafx.application.Application;

// Điểm vào riêng cho bản đóng gói jpackage. JavaFX từ chối chạy khi class main kế thừa Application
// mà javafx.graphics không nằm trên module path, trong khi jpackage nhét mọi jar vào classpath.
// Cho điểm vào là một class không kế thừa Application thì phép kiểm tra đó không chạy.
public final class Launcher
{
    private Launcher()
    {
    }

    public static void main(String[] args)
    {
        Application.launch(DictApp.class, args);
    }
}
