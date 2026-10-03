package com.anhtuan.dict.desktop.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Tìm thư mục dữ liệu (dict.pack cùng các file .idx), dừng ngay khi thấy dict.pack.
// Thứ tự: -Ddict.data, thư mục app/data cạnh bản cài, data/build khi chạy từ mã nguồn,
// rồi ~/.offline-dict/data của người dùng.
// Dữ liệu đi kèm bản cài phải xét TRƯỚC hai đường dẫn tương đối, vì chúng tính theo thư mục hiện hành
// chứ không theo chỗ đặt ứng dụng: mở bản portable từ một cửa sổ lệnh đang đứng trong thư mục mã
// nguồn sẽ đọc nhầm dữ liệu của mã nguồn mà không báo lỗi.
public final class DataLocator
{
    public static final String PACK_FILE = "dict.pack";
    private static final String PROPERTY = "dict.data";

    private DataLocator()
    {
    }

    public static Path locate()
    {
        List<Path> tried = new ArrayList<>(6);
        for (Path p : candidates())
        {
            tried.add(p);
            if (p != null && Files.isRegularFile(p.resolve(PACK_FILE)))
            {
                return p;
            }
        }
        throw new IllegalStateException("""
                Cannot find %s.
                Tried: %s
                Build the data first:
                  java -cp "dict-core/target/classes;dict-importer/target/classes" \\
                       com.anhtuan.dict.importer.cli.ImporterMain build anhviet109K.txt data/build
                """.formatted(PACK_FILE, tried));
    }

    private static List<Path> candidates()
    {
        List<Path> out = new ArrayList<>(6);
        String property = System.getProperty(PROPERTY);
        if (property != null && !property.isBlank())
        {
            out.add(Path.of(property));
        }
        out.add(appDir().resolve("data"));
        out.add(Path.of("data", "build"));
        out.add(Path.of("..", "data", "build"));
        out.add(Path.of(System.getProperty("user.home"), ".offline-dict", "data"));
        return out;
    }

    // Thư mục chứa jar / class đang chạy, để bản jpackage tìm thấy dữ liệu nằm cạnh nó
    private static Path appDir()
    {
        try
        {
            Path self = Path.of(DataLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(self) ? self : self.getParent();
        }
        catch (Exception e)
        {
            return Path.of(".");
        }
    }
}
