package com.anhtuan.dict.desktop;

import com.anhtuan.dict.desktop.config.AppContext;
import com.anhtuan.dict.desktop.ui.AppIcon;
import com.anhtuan.dict.desktop.ui.MainView;
import com.anhtuan.dict.desktop.ui.MissingDataView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

// Điểm vào của app. Nạp dữ liệu ở init() (trước khi cửa sổ hiện) để cửa sổ không hiện ra rồi treo.
// Không dùng Spring: các đối tượng được ráp tay trong AppContext, xem lý do ở đó.
public final class DictApp extends Application
{
    private AppContext ctx;
    private String failure;

    @Override
    public void init()
    {
        try
        {
            ctx = new AppContext();
        }
        catch (RuntimeException e)
        {
            // Thiếu dữ liệu là lỗi thường gặp nhất khi chạy lần đầu: báo ngay trên cửa sổ
            failure = e.getMessage();
        }
    }

    @Override
    public void start(Stage stage)
    {
        if (ctx == null)
        {
            AppIcon.applyTo(stage);
            MissingDataView.show(stage, failure);
            return;
        }
        new MainView(ctx, getParameters().getRaw()).show(stage);
    }

    @Override
    public void stop()
    {
        // Đóng để các file mmap được giải phóng ngay
        if (ctx != null)
        {
            ctx.close();
        }
        Platform.exit();
    }

    public static void main(String[] args)
    {
        launch(args);
    }
}
