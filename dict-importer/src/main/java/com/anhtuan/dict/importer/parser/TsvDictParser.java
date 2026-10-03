package com.anhtuan.dict.importer.parser;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.spi.DictParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

// Đọc từ điển dạng bảng: mỗi dòng một mục từ, các cột ngăn bằng TAB:
//   headword <TAB> nghĩa tiếng Việt [<TAB> từ loại]
// Chọn TSV vì người dùng tự làm được (mở Excel, gõ hai cột, lưu .tsv). Nhiều nghĩa ngăn bằng dấu
// phẩy trong một ô, giống nguồn 109K, nên phần xử lý phía sau dùng lại nguyên.
public final class TsvDictParser implements DictParser
{

    public static final String FORMAT_ID = "tsv";

    @Override
    public String formatId()
    {
        return FORMAT_ID;
    }

    @Override
    public boolean canParse(Path source)
    {
        if (source == null || !Files.isRegularFile(source))
            return false;
        String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (!name.endsWith(".tsv") && !name.endsWith(".txt"))
            return false;
        try (BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8))
        {
            for (int i = 0; i < 20; i++)
            {
                String line = r.readLine();
                if (line == null)
                    break;
                line = TextNormalizer.stripBom(line);
                if (line.isBlank() || line.charAt(0) == '#')
                    continue;
                // Nguồn kiểu 109K bắt đầu bằng '@'; nhận nhầm parser sẽ hỏng cả dữ liệu.
                if (line.charAt(0) == '@')
                    return false;
                return line.indexOf('\t') > 0;
            }
        }
        catch (IOException e)
        {
            return false;
        }
        return false;
    }

    @Override
    public Stream<Entry> parse(Path source, int sourceId)
    {
        final BufferedReader reader;
        try
        {
            reader = Files.newBufferedReader(source, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot open " + source, e);
        }
        return reader.lines().map(TextNormalizer::stripBom).filter(line -> !line.isBlank() && line.charAt(0) != '#')
                .map(line -> toEntry(line, sourceId)).filter(java.util.Objects::nonNull).onClose(() ->
                {
                    try
                    {
                        reader.close();
                    }
                    catch (IOException e)
                    {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    // Dòng sai định dạng trả về null và bị bỏ qua, không được ném lỗi.
    static Entry toEntry(String line, int sourceId)
    {
        String[] columns = line.split("\t", -1);
        if (columns.length < 2)
            return null;
        String headword = columns[0].trim();
        String gloss = columns[1].trim();
        if (headword.isEmpty() || gloss.isEmpty())
            return null;
        String pos = columns.length >= 3 && !columns[2].isBlank() ? columns[2].trim() : null;

        return new Entry(headword, TextNormalizer.normalizeHeadword(headword), null, null,
                List.of(new Sense(pos, List.of(gloss), List.of())), List.of(), List.of(), sourceId);
    }
}
