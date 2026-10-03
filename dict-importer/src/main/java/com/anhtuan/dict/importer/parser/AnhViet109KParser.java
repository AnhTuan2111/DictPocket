package com.anhtuan.dict.importer.parser;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Example;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;
import com.anhtuan.dict.core.spi.DictParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

// Parser cho từ điển kiểu Lạc Việt / StarDict text (anhviet109K.txt), là máy trạng thái đọc
// theo dòng, phân nhánh bằng ký tự đầu dòng:
//   '@' headword mới    -> đóng entry cũ, mở entry mới
//   '*' từ loại         -> đóng sense cũ, mở sense mới, thoát chế độ idiom
//   '-' nghĩa           -> vào idiom đang mở nếu đang ở chế độ idiom, ngược lại vào sense
//   '=' ví dụ           -> gắn vào idiom đang mở hoặc sense đang mở
//   '!' thành ngữ       -> đóng idiom cũ, mở idiom mới, vào chế độ idiom
// Bẫy lớn nhất là '!': sau đó các dòng '-' không còn thuộc sense đang mở; quên điều này thì
// nghĩa thành ngữ bị trộn vào nghĩa từ gốc mà không báo lỗi.
public final class AnhViet109KParser implements DictParser
{

    public static final String FORMAT_ID = "anhviet109k";

    // Đếm các dòng không khớp văn phạm, để kiểm tra sau khi parse xem dữ liệu có lạ không.
    private final AtomicLong malformedLines = new AtomicLong();
    private final List<String> malformedSamples = new ArrayList<>();

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
        try (BufferedReader r = newReader(source))
        {
            for (int i = 0; i < 20; i++)
            {
                String line = r.readLine();
                if (line == null)
                    break;
                if (TextNormalizer.stripBom(line).startsWith("@"))
                    return true;
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
            reader = newReader(source);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot open source file: " + source, e);
        }
        EntryIterator it = new EntryIterator(reader, sourceId);
        return StreamSupport
                .stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED | Spliterator.NONNULL), false)
                .onClose(() ->
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

    public long malformedLineCount()
    {
        return malformedLines.get();
    }

    // Tối đa 20 dòng lỗi đầu tiên, để xem nhanh dữ liệu hỏng kiểu gì.
    public List<String> malformedSamples()
    {
        synchronized (malformedSamples)
        {
            return List.copyOf(malformedSamples);
        }
    }

    private static BufferedReader newReader(Path source) throws IOException
    {
        // BOM ở đầu file được TextNormalizer.stripBom xử lý ở dòng đầu tiên.
        return Files.newBufferedReader(source, StandardCharsets.UTF_8);
    }

    private void reportMalformed(long lineNo, String line)
    {
        malformedLines.incrementAndGet();
        synchronized (malformedSamples)
        {
            if (malformedSamples.size() < 20)
            {
                malformedSamples.add("line " + lineNo + ": " + line);
            }
        }
    }

    private final class EntryIterator implements Iterator<Entry>
    {
        private final BufferedReader reader;
        private final int sourceId;

        private String pendingHeadwordLine; // dòng '@' của entry TIẾP THEO, đọc trước rồi giữ lại
        private Entry next;
        private boolean exhausted;
        private long lineNo;

        EntryIterator(BufferedReader reader, int sourceId)
        {
            this.reader = reader;
            this.sourceId = sourceId;
        }

        @Override
        public boolean hasNext()
        {
            if (next == null && !exhausted)
                next = readEntry();
            return next != null;
        }

        @Override
        public Entry next()
        {
            if (!hasNext())
                throw new java.util.NoSuchElementException();
            Entry e = next;
            next = null;
            return e;
        }

        private Entry readEntry()
        {
            try
            {
                String headwordLine = pendingHeadwordLine;
                pendingHeadwordLine = null;

                // Bỏ qua rác cho tới khi gặp dòng '@' đầu tiên
                while (headwordLine == null)
                {
                    String line = readLine();
                    if (line == null)
                    {
                        exhausted = true;
                        return null;
                    }
                    if (line.startsWith("@"))
                    {
                        headwordLine = line;
                    }
                    else if (!line.isBlank())
                    {
                        reportMalformed(lineNo, line);
                    }
                }
                return parseEntryBody(headwordLine);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(e);
            }
        }

        private String readLine() throws IOException
        {
            String line = reader.readLine();
            if (line == null)
                return null;
            lineNo++;
            if (lineNo == 1)
                line = TextNormalizer.stripBom(line);
            return line;
        }

        private Entry parseEntryBody(String headwordLine) throws IOException
        {
            HeadwordParts hp = splitHeadword(headwordLine.substring(1));

            List<Sense> senses = new ArrayList<>(2);
            List<Idiom> idioms = new ArrayList<>(0);
            List<String> crossRefs = new ArrayList<>(0);

            // Sense đang mở
            String currentPos = null;
            List<String> senseGlosses = new ArrayList<>(4);
            List<Example> senseExamples = new ArrayList<>(0);
            boolean senseOpen = false;

            // Idiom đang mở; idiomOpen == true là "chế độ idiom": mọi dòng '-' và '='
            // chạy vào idiom chứ không vào sense.
            String idiomPhrase = null;
            List<String> idiomGlosses = new ArrayList<>(2);
            List<Example> idiomExamples = new ArrayList<>(0);
            boolean idiomOpen = false;

            String line;
            boolean endOfEntry = false;
            while (!endOfEntry && (line = readLine()) != null)
            {
                if (line.isBlank())
                    continue;

                char marker = line.charAt(0);
                String rest = line.substring(1).trim();

                switch (marker)
                {
                    case '@' -> {
                        pendingHeadwordLine = line; // để dành cho entry sau
                        endOfEntry = true;
                    }
                    case '*' -> { // từ loại mới
                        if (idiomOpen)
                        { // '!' có thể nằm giữa hai khối '*'
                            idioms.add(new Idiom(idiomPhrase, idiomGlosses, idiomExamples));
                            idiomGlosses = new ArrayList<>(2);
                            idiomExamples = new ArrayList<>(0);
                            idiomOpen = false;
                        }
                        if (senseOpen)
                        {
                            senses.add(new Sense(currentPos, senseGlosses, senseExamples));
                            senseGlosses = new ArrayList<>(4);
                            senseExamples = new ArrayList<>(0);
                        }
                        currentPos = rest.isEmpty() ? null : rest;
                        senseOpen = true;
                    }
                    case '!' -> { // thành ngữ mới
                        if (idiomOpen)
                        {
                            idioms.add(new Idiom(idiomPhrase, idiomGlosses, idiomExamples));
                            idiomGlosses = new ArrayList<>(2);
                            idiomExamples = new ArrayList<>(0);
                        }
                        idiomPhrase = TextNormalizer.collapseSpaces(rest.replace('_', ' '));
                        idiomOpen = true;
                    }
                    case '-' -> { // nghĩa
                        if (!rest.isEmpty())
                        {
                            if (idiomOpen)
                            {
                                idiomGlosses.add(rest); // bẫy: nghĩa sau '!' thuộc về idiom
                            }
                            else
                            {
                                senseOpen = true; // entry không có '*' nào
                                senseGlosses.add(rest);
                            }
                        }
                    }
                    case '=' -> { // ví dụ
                        Example ex = parseExample(rest, idiomOpen ? idiomGlosses.size() : senseGlosses.size());
                        if (ex != null)
                        {
                            if (idiomOpen)
                                idiomExamples.add(ex);
                            else
                            {
                                senseOpen = true;
                                senseExamples.add(ex);
                            }
                        }
                    }
                    case '+' -> { // tham chiếu chéo
                        if (!rest.isEmpty())
                            crossRefs.add(rest);
                    }
                    default -> reportMalformed(lineNo, line); // bỏ qua, không throw
                }
            }

            if (idiomOpen)
                idioms.add(new Idiom(idiomPhrase, idiomGlosses, idiomExamples));
            if (senseOpen)
                senses.add(new Sense(currentPos, senseGlosses, senseExamples));

            return new Entry(hp.headword(), TextNormalizer.normalizeHeadword(hp.headword()), hp.ipa(), hp.variant(),
                    senses, idioms, crossRefs, sourceId);
        }
    }

    record HeadwordParts(String headword, String ipa, String variant)
    {
    }

    // Tách dòng headword thành headword + phiên âm + dạng viết khác. Ba dạng trong file nguồn:
    // @about /ə'baut/ -> head, ipa
    // @and/or -> head (tự nó chứa '/'), không ipa
    // @acid-proof /'æsid'pru:f/ (acid-resisting) /'æsidri.../ -> head, ipa, variant
    // Headword kết thúc ở dấu '/' ĐẦU TIÊN có khoảng trắng đứng trước. Không lấy '/' cuối: 7.128
    // dòng có dạng biến thể, lấy từ phải sang sẽ nuốt "(acid-resisting)" vào headword và làm
    // phồng số cụm nhiều từ từ 11.956 lên 18.862.
    static HeadwordParts splitHeadword(String raw)
    {
        String s = TextNormalizer.stripBom(raw).trim();

        int slash = indexOfSpaceSlash(s);
        if (slash < 0)
        {
            return new HeadwordParts(normalizeDisplay(s), null, null); // "and/or", "ajutage"
        }

        String head = s.substring(0, slash).trim();
        if (head.isEmpty())
        {
            return new HeadwordParts(normalizeDisplay(s), null, null);
        }

        String tail = s.substring(slash + 1);
        int close = tail.indexOf('/');
        String ipa = (close < 0) ? tail.trim() : tail.substring(0, close).trim();
        String rest = (close < 0) ? "" : tail.substring(close + 1).trim();

        return new HeadwordParts(normalizeDisplay(head), ipa.isEmpty() ? null : ipa, extractVariant(rest));
    }

    // Vị trí dấu '/' đầu tiên mà trước nó là khoảng trắng; -1 nếu không có.
    private static int indexOfSpaceSlash(String s)
    {
        for (int i = 1; i < s.length(); i++)
        {
            if (s.charAt(i) == '/' && Character.isWhitespace(s.charAt(i - 1)))
                return i;
        }
        return -1;
    }

    // Lấy "acid-resisting" từ phần còn lại "(acid-resisting) /'æsidri'zistiɳ/".
    private static String extractVariant(String rest)
    {
        if (rest.isEmpty())
            return null;
        int open = rest.indexOf('(');
        int close = rest.indexOf(')', open + 1);
        if (open < 0 || close < 0)
            return null;
        String v = normalizeDisplay(rest.substring(open + 1, close).trim());
        return v.isEmpty() ? null : v;
    }

    // Dạng hiển thị: bỏ dấu nối '_' của định dạng gốc, gộp khoảng trắng, giữ nguyên hoa/thường.
    static String normalizeDisplay(String s)
    {
        return TextNormalizer.collapseSpaces(s.replace('_', ' '));
    }

    // Tách "he is about+ anh ta quanh đây" thành en + vi; không có '+' thì vẫn giữ ví dụ, vi = null.
    static Example parseExample(String rest, int glossCount)
    {
        if (rest.isEmpty())
            return null;
        int plus = rest.indexOf('+');
        int glossIndex = glossCount - 1; // gắn vào dòng '-' gần nhất phía trên
        if (plus < 0)
        {
            return new Example(normalizeDisplay(rest), null, glossIndex);
        }
        String en = normalizeDisplay(rest.substring(0, plus).trim());
        String vi = rest.substring(plus + 1).trim();
        if (en.isEmpty())
            return null;
        return new Example(en, vi.isEmpty() ? null : vi, glossIndex);
    }
}
