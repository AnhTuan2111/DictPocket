package com.anhtuan.dict.desktop.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

// Phiên bản và loại bản phát hành, đọc từ version.properties do Maven sinh lúc build.
// Hai bản (standard, ai) dựng từ cùng một mã nguồn, chỉ khác bộ đóng gói có mang mô hình AI hay không.
public final class AppVersion
{
    private static final Properties PROPERTIES = load();

    private AppVersion()
    {
    }

    private static Properties load()
    {
        Properties p = new Properties();
        try (InputStream in = AppVersion.class.getResourceAsStream("/version.properties"))
        {
            if (in != null)
            {
                p.load(in);
            }
        }
        catch (IOException e)
        {
            // Chạy từ IDE khi chưa build thì thiếu file, không phải lý do để app không chạy
        }
        return p;
    }

    public static String version()
    {
        return PROPERTIES.getProperty("version", "dev");
    }

    // "standard" hoặc "ai"
    public static String edition()
    {
        return PROPERTIES.getProperty("edition", "standard");
    }

    public static boolean isAiEdition()
    {
        return "ai".equals(edition());
    }

    // Chuỗi hiện ở thanh trạng thái: "v1.1.0 · bản thường"
    public static String display()
    {
        return "v" + version() + " · " + (isAiEdition() ? "bản AI" : "bản thường");
    }
}
