package com.anhtuan.dict.core.source;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

// Danh mục các nguồn từ điển: bật/tắt và đổi thứ tự ưu tiên.
// Mỗi Entry trong pack mang sẵn sourceId nên tắt một nguồn chỉ là lọc bỏ entry có sourceId đó lúc tra cứu,
// không đụng file dữ liệu. Thêm nguồn mới thì phải sinh lại pack (~5 giây): pack là file bất biến nên mới nhỏ và nhanh.
// sources.tsv là văn bản, mỗi dòng một nguồn, ngăn bằng TAB, người dùng sửa tay được:
//   id  name            format       file             entries  enabled  priority
//   0   Anh-Việt 109K   anhviet109k  anhviet109K.txt  108854   1        2
//   1   Thuật ngữ CNTT  tsv          it-terms.tsv     463      1        0
public final class SourceCatalog
{

    public static final String FILE_NAME = "sources.tsv";

    private final List<DictSource> sources;

    private SourceCatalog(List<DictSource> sources)
    {
        this.sources = new ArrayList<>(sources);
        this.sources.sort(Comparator.comparingInt(DictSource::priority).thenComparingInt(DictSource::id));
    }

    public static SourceCatalog of(List<DictSource> sources)
    {
        return new SourceCatalog(sources);
    }

    // Không có file thì trả về danh mục một nguồn mặc định để dữ liệu cũ vẫn chạy được.
    public static SourceCatalog loadOrDefault(Path file, String defaultName, int entryCount)
    {
        if (!Files.isRegularFile(file))
        {
            return new SourceCatalog(List.of(new DictSource(0, defaultName, "anhviet109k", "", entryCount, true, 0)));
        }
        try
        {
            List<DictSource> out = new ArrayList<>(4);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
            {
                if (line.isBlank() || line.charAt(0) == '#')
                    continue;
                String[] c = line.split("\t", -1);
                if (c.length < 7)
                    continue;
                out.add(new DictSource(Integer.parseInt(c[0].trim()), c[1].trim(), c[2].trim(), c[3].trim(),
                        Integer.parseInt(c[4].trim()), "1".equals(c[5].trim()), Integer.parseInt(c[6].trim())));
            }
            return out.isEmpty()
                    ? loadOrDefault(Path.of("nonexistent"), defaultName, entryCount)
                    : new SourceCatalog(out);
        }
        catch (IOException | NumberFormatException e)
        {
            throw new IllegalStateException("file " + file + " is corrupt: " + e.getMessage(), e);
        }
    }

    public void save(Path file)
    {
        List<String> lines = new ArrayList<>(sources.size() + 2);
        lines.add("# id\tname\tformat\tfile\tentries\tenabled(1/0)\tpriority");
        for (DictSource s : sources)
        {
            lines.add(String.join("\t", String.valueOf(s.id()), s.name(), s.format(), s.file(),
                    String.valueOf(s.entries()), s.enabled() ? "1" : "0", String.valueOf(s.priority())));
        }
        try
        {
            Files.write(file, lines, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    // Tất cả nguồn, đã sắp theo ưu tiên.
    public List<DictSource> all()
    {
        return List.copyOf(sources);
    }

    public int size()
    {
        return sources.size();
    }

    // Id của các nguồn đang bật; rỗng nghĩa là người dùng tắt hết, lúc đó không lọc gì cả.
    public Set<Integer> enabledIds()
    {
        Set<Integer> out = new LinkedHashSet<>(sources.size());
        for (DictSource s : sources)
            if (s.enabled())
                out.add(s.id());
        return out;
    }

    // Nguồn này có đang bật không; nguồn lạ (không có trong danh mục) coi như bật.
    public boolean isEnabled(int sourceId)
    {
        for (DictSource s : sources)
            if (s.id() == sourceId)
                return s.enabled();
        return true;
    }

    // Thứ tự ưu tiên của một nguồn; nguồn lạ xếp sau cùng.
    public int priorityOf(int sourceId)
    {
        for (DictSource s : sources)
            if (s.id() == sourceId)
                return s.priority();
        return Integer.MAX_VALUE;
    }

    // Còn ít nhất một nguồn đang bật không; tắt hết thì UI phải báo, không để màn hình trống.
    public boolean hasEnabled()
    {
        return sources.stream().anyMatch(DictSource::enabled);
    }

    public SourceCatalog setEnabled(int sourceId, boolean enabled)
    {
        List<DictSource> out = new ArrayList<>(sources.size());
        for (DictSource s : sources)
            out.add(s.id() == sourceId ? s.withEnabled(enabled) : s);
        return new SourceCatalog(out);
    }

    // Đẩy một nguồn lên trên hoặc xuống dưới một bậc trong thứ tự ưu tiên.
    public SourceCatalog move(int sourceId, int delta)
    {
        List<DictSource> ordered = new ArrayList<>(sources);
        int at = -1;
        for (int i = 0; i < ordered.size(); i++)
            if (ordered.get(i).id() == sourceId)
                at = i;
        int to = at + delta;
        if (at < 0 || to < 0 || to >= ordered.size())
            return this;
        DictSource moved = ordered.remove(at);
        ordered.add(to, moved);
        List<DictSource> renumbered = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++)
            renumbered.add(ordered.get(i).withPriority(i));
        return new SourceCatalog(renumbered);
    }
}
