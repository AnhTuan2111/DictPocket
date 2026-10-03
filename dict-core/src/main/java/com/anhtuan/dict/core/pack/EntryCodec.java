package com.anhtuan.dict.core.pack;

import com.anhtuan.dict.core.model.Entry;
import com.anhtuan.dict.core.model.Example;
import com.anhtuan.dict.core.model.Idiom;
import com.anhtuan.dict.core.model.Sense;
import com.anhtuan.dict.core.nlp.TextNormalizer;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// Serialize/deserialize một entry trong block. PackWriter và PackReader phải dùng chung class này,
// nếu không chỉ cần lệch một varint là đọc ra rác mà không báo lỗi.
// Định dạng (varint = LEB128 không dấu, str = varint(số byte) + byte UTF-8):
//   entry   := str headword, str ipa, str variant, varint sourceId,
//              varint nSense, sense[nSense],
//              varint nIdiom, idiom[nIdiom],
//              varint nRef,   str[nRef]
//   sense   := str pos, varint nGloss, str[nGloss], varint nEx, example[nEx]
//   idiom   := str phrase, varint nGloss, str[nGloss], varint nEx, example[nEx]
//   example := str en, str vi, varint (glossIndex + 1)
// sourceId phải lưu để round-trip ra đúng entry gốc.
// glossIndex lưu +1 vì varint không dấu: giá trị -1 sẽ tốn 5 byte x 34.000 ví dụ = 170 KB vô ích.
// Chuỗi rỗng khi đọc ra là null (ipa, variant, pos, vi).
// headwordNorm không lưu trong block: tính lại bằng TextNormalizer khi đọc, tiết kiệm ~1,1 MB.
public final class EntryCodec
{
    private EntryCodec()
    {
    }

    public static void writeEntry(ByteArrayOutputStream out, Entry e)
    {
        writeStr(out, e.headword());
        writeStr(out, e.ipa());
        writeStr(out, e.variant());
        VarInt.write(out, e.sourceId());

        VarInt.write(out, e.senses().size());
        for (Sense s : e.senses())
        {
            writeStr(out, s.pos());
            VarInt.write(out, s.glosses().size());
            for (String g : s.glosses())
                writeStr(out, g);
            VarInt.write(out, s.examples().size());
            for (Example ex : s.examples())
                writeExample(out, ex);
        }

        VarInt.write(out, e.idioms().size());
        for (Idiom i : e.idioms())
        {
            writeStr(out, i.phrase());
            VarInt.write(out, i.glosses().size());
            for (String g : i.glosses())
                writeStr(out, g);
            VarInt.write(out, i.examples().size());
            for (Example ex : i.examples())
                writeExample(out, ex);
        }

        VarInt.write(out, e.crossRefs().size());
        for (String r : e.crossRefs())
            writeStr(out, r);
    }

    private static void writeExample(ByteArrayOutputStream out, Example ex)
    {
        writeStr(out, ex.en());
        writeStr(out, ex.vi());
        VarInt.write(out, ex.glossIndex() + 1);
    }

    private static void writeStr(ByteArrayOutputStream out, String s)
    {
        if (s == null || s.isEmpty())
        {
            VarInt.write(out, 0);
            return;
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        VarInt.write(out, b.length);
        out.write(b, 0, b.length);
    }

    public static Entry readEntry(ByteBuffer buf)
    {
        String headword = readStr(buf);
        String ipa = emptyToNull(readStr(buf));
        String variant = emptyToNull(readStr(buf));
        int sourceId = VarInt.read(buf);

        int nSense = VarInt.read(buf);
        List<Sense> senses = new ArrayList<>(nSense);
        for (int i = 0; i < nSense; i++)
        {
            String pos = emptyToNull(readStr(buf));
            senses.add(new Sense(pos, readStrList(buf), readExamples(buf)));
        }

        int nIdiom = VarInt.read(buf);
        List<Idiom> idioms = new ArrayList<>(nIdiom);
        for (int i = 0; i < nIdiom; i++)
        {
            String phrase = readStr(buf);
            idioms.add(new Idiom(phrase, readStrList(buf), readExamples(buf)));
        }

        List<String> refs = readStrList(buf);

        return new Entry(headword, TextNormalizer.normalizeHeadword(headword), ipa, variant, senses, idioms, refs,
                sourceId);
    }

    // Nhảy qua một entry mà không tạo String, dùng khi tìm entry thứ k trong block.
    public static void skipEntry(ByteBuffer buf)
    {
        skipStr(buf);
        skipStr(buf);
        skipStr(buf); // headword, ipa, variant
        VarInt.read(buf); // sourceId

        int nSense = VarInt.read(buf);
        for (int i = 0; i < nSense; i++)
        {
            skipStr(buf); // pos
            skipStrList(buf); // glosses
            skipExamples(buf);
        }
        int nIdiom = VarInt.read(buf);
        for (int i = 0; i < nIdiom; i++)
        {
            skipStr(buf); // phrase
            skipStrList(buf);
            skipExamples(buf);
        }
        skipStrList(buf); // crossRefs
    }

    private static List<Example> readExamples(ByteBuffer buf)
    {
        int n = VarInt.read(buf);
        List<Example> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
        {
            String en = readStr(buf);
            String vi = emptyToNull(readStr(buf));
            int glossIndex = VarInt.read(buf) - 1;
            out.add(new Example(en, vi, glossIndex));
        }
        return out;
    }

    private static void skipExamples(ByteBuffer buf)
    {
        int n = VarInt.read(buf);
        for (int i = 0; i < n; i++)
        {
            skipStr(buf);
            skipStr(buf);
            VarInt.read(buf);
        }
    }

    private static List<String> readStrList(ByteBuffer buf)
    {
        int n = VarInt.read(buf);
        List<String> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++)
            out.add(readStr(buf));
        return out;
    }

    private static void skipStrList(ByteBuffer buf)
    {
        int n = VarInt.read(buf);
        for (int i = 0; i < n; i++)
            skipStr(buf);
    }

    private static String readStr(ByteBuffer buf)
    {
        int len = VarInt.read(buf);
        if (len == 0)
            return "";
        byte[] b = new byte[len];
        buf.get(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void skipStr(ByteBuffer buf)
    {
        int len = VarInt.read(buf);
        buf.position(buf.position() + len);
    }

    private static String emptyToNull(String s)
    {
        return s.isEmpty() ? null : s;
    }
}
