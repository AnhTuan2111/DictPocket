package com.anhtuan.dict.desktop.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tim thu muc du lieu (dict.pack + cac file .idx).
 *
 * <p>Thu tu tim, dung ngay khi thay dict.pack:
 * <ol>
 *   <li>{@code -Ddict.data=<duong dan>} - de chay tu IDE hoac chi ra du lieu khac</li>
 *   <li>{@code <thu muc cai dat>/app/data} - khi chay ban da jpackage</li>
 *   <li>{@code ./data/build} va {@code ../data/build} - khi chay tu trong ma nguon</li>
 *   <li>{@code ~/.offline-dict/data} - noi nguoi dung tu dat them tu dien (F5)</li>
 * </ol>
 *
 * <p>Du lieu DI KEM ban cai phai duoc xet TRUOC hai duong dan tuong doi, vi hai duong dan do
 * tinh theo THU MUC HIEN HANH chu khong theo cho dat ung dung. Luc dau xep nguoc lai va da
 * do that: mo ban portable tu mot cua so dong lenh dang dung o thu muc ma nguon thi no doc
 * du lieu cua ma nguon, khong doc du lieu minh mang theo - ma thanh trang thai van bao la
 * chay binh thuong. Dung tren may nguoi dung vi ho khong co thu muc do, nhung day van la
 * mot ung dung doc nham du lieu ma khong he keu len mot tieng.
 */
public final class DataLocator {

    public static final String PACK_FILE = "dict.pack";
    private static final String PROPERTY = "dict.data";

    private DataLocator() {}

    public static Path locate() {
        List<Path> tried = new ArrayList<>(6);
        for (Path p : candidates()) {
            tried.add(p);
            if (p != null && Files.isRegularFile(p.resolve(PACK_FILE))) return p;
        }
        throw new IllegalStateException("""
                Khong tim thay %s.
                Da thu: %s
                Chay lenh nay de sinh du lieu:
                  java -cp "dict-core/target/classes;dict-importer/target/classes" \\
                       com.anhtuan.dict.importer.cli.ImporterMain build anhviet109K.txt data/build
                """.formatted(PACK_FILE, tried));
    }

    private static List<Path> candidates() {
        List<Path> out = new ArrayList<>(6);
        String prop = System.getProperty(PROPERTY);
        if (prop != null && !prop.isBlank()) out.add(Path.of(prop));
        out.add(appDir().resolve("data"));
        out.add(Path.of("data", "build"));
        out.add(Path.of("..", "data", "build"));
        out.add(Path.of(System.getProperty("user.home"), ".offline-dict", "data"));
        return out;
    }

    /** Thu muc chua file jar/class dang chay - de ban jpackage tim duoc du lieu ben canh no. */
    private static Path appDir() {
        try {
            Path self = Path.of(DataLocator.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return Files.isDirectory(self) ? self : self.getParent();
        } catch (Exception e) {
            return Path.of(".");
        }
    }
}
