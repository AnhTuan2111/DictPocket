package com.anhtuan.dict.desktop.userdata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

// Lịch sử tra lưu trên máy người dùng, không gửi đi đâu. File văn bản thuần, mỗi dòng một lượt tra:
//   <giây epoch> TAB <chế độ> TAB <nội dung>
// Chỉ ghi thêm vào cuối nên rất nhanh; xoá thì ghi lại cả file. Mọi lỗi đọc/ghi đều bị nuốt: mất lịch sử
// không đáng để app hỏng, khi đó lịch sử chỉ còn sống trong bộ nhớ đến lúc thoát.
public final class HistoryStore
{
    public record Entry(long epochSecond, String mode, String query)
    {
    }

    private static final int MAX_ENTRIES = 1000;
    // Chỉ dọn file khi vượt hẳn mức này, để không ghi lại cả file ở mỗi lượt tra
    private static final int COMPACT_ABOVE = 2000;
    private static final int MAX_QUERY_CHARS = 5000;

    // null nghĩa là chỉ giữ trong bộ nhớ (ví dụ lúc chụp ảnh tài liệu)
    private final Path file;
    private final Clock clock;
    // Từ cũ đến mới
    private final List<Entry> log = new ArrayList<>();

    private HistoryStore(Path file, Clock clock)
    {
        this.file = file;
        this.clock = clock;
    }

    public static HistoryStore open(Path file)
    {
        return open(file, Clock.systemUTC());
    }

    static HistoryStore open(Path file, Clock clock)
    {
        HistoryStore store = new HistoryStore(file, clock);
        store.load();
        return store;
    }

    public static HistoryStore inMemory()
    {
        return new HistoryStore(null, Clock.systemUTC());
    }

    public Path file()
    {
        return file;
    }

    // Ghi một lượt tra. Nội dung rỗng và lượt lặp liền ngay trước bị bỏ qua.
    public void add(String mode, String query)
    {
        String clean = sanitize(query);
        if (clean.isEmpty())
        {
            return;
        }
        if (!log.isEmpty())
        {
            Entry last = log.getLast();
            if (last.mode().equals(mode) && last.query().equals(clean))
            {
                return;
            }
        }
        Entry entry = new Entry(clock.instant().getEpochSecond(), mode, clean);
        log.add(entry);
        if (log.size() > COMPACT_ABOVE)
        {
            trimToLimit();
            rewrite();
        }
        else
        {
            append(entry);
        }
    }

    // Mới nhất trước, mỗi (chế độ, nội dung) chỉ hiện một lần với thời điểm tra gần nhất
    public List<Entry> entries()
    {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = log.size() - 1; i >= 0 && out.size() < MAX_ENTRIES; i--)
        {
            Entry e = log.get(i);
            if (seen.add(e.mode() + '\t' + e.query()))
            {
                out.add(e);
            }
        }
        return out;
    }

    // Xoá mọi lượt có cùng chế độ và nội dung
    public void remove(Entry entry)
    {
        if (log.removeIf(e -> e.mode().equals(entry.mode()) && e.query().equals(entry.query())))
        {
            rewrite();
        }
    }

    public void clear()
    {
        log.clear();
        rewrite();
    }

    private void load()
    {
        if (file == null || !Files.isRegularFile(file))
        {
            return;
        }
        try
        {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                String[] parts = line.split("\t", 3);
                if (parts.length < 3 || parts[1].isEmpty() || parts[2].isBlank())
                {
                    continue;
                }
                try
                {
                    log.add(new Entry(Long.parseLong(parts[0].trim()), parts[1], parts[2]));
                }
                catch (NumberFormatException e)
                {
                    // Dòng hỏng thì bỏ qua
                }
            }
        }
        catch (IOException e)
        {
            // Không đọc được thì coi như chưa có lịch sử
            return;
        }
        if (log.size() > COMPACT_ABOVE)
        {
            trimToLimit();
            rewrite();
        }
    }

    private void trimToLimit()
    {
        if (log.size() > MAX_ENTRIES)
        {
            List<Entry> keep = new ArrayList<>(log.subList(log.size() - MAX_ENTRIES, log.size()));
            log.clear();
            log.addAll(keep);
        }
    }

    private void append(Entry entry)
    {
        if (file == null)
        {
            return;
        }
        try
        {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, format(entry), StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        catch (IOException e)
        {
            // Giữ trong bộ nhớ, xem chú thích đầu lớp
        }
    }

    private void rewrite()
    {
        if (file == null)
        {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : log)
        {
            sb.append(format(e));
        }
        try
        {
            Files.createDirectories(file.toAbsolutePath().getParent());
            // Ghi ra file tạm rồi đổi tên, để mất điện giữa chừng không làm hỏng lịch sử cũ
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e)
            {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException e)
        {
            // Giữ trong bộ nhớ, xem chú thích đầu lớp
        }
    }

    private static String format(Entry e)
    {
        return e.epochSecond() + "\t" + e.mode() + "\t" + e.query() + "\n";
    }

    // Một lượt tra nằm trên một dòng: xuống dòng và Tab trong nội dung đổi thành dấu cách
    private static String sanitize(String query)
    {
        if (query == null)
        {
            return "";
        }
        String s = query.replaceAll("[\\t\\r\\n]+", " ").strip();
        return s.length() > MAX_QUERY_CHARS ? s.substring(0, MAX_QUERY_CHARS) : s;
    }
}
