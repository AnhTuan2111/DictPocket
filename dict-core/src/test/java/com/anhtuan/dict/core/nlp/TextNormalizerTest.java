package com.anhtuan.dict.core.nlp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class TextNormalizerTest
{

    @Test
    @DisplayName("normalizeHeadword: replaces '_' with space, collapses spaces, lowercases")
    void normalizesHeadword()
    {
        assertEquals("a la carte", TextNormalizer.normalizeHeadword("a_la_carte"));
        assertEquals("give up", TextNormalizer.normalizeHeadword("  Give   Up  "));
        assertEquals("about to", TextNormalizer.normalizeHeadword("About To"));
    }

    @Test
    @DisplayName("normalizeHeadword uses Locale.ROOT, avoiding the Turkish locale trap")
    void usesRootLocaleNotDefault()
    {
        Locale original = Locale.getDefault();
        try
        {
            // Trong locale tiếng Thổ Nhĩ Kỳ, "I".toLowerCase() ra "ı" chứ không phải "i"; dùng
            // locale mặc định thì khoá sinh ra lệch và tra "IT" không bao giờ ra kết quả.
            Locale.setDefault(Locale.forLanguageTag("tr"));
            assertEquals("it", TextNormalizer.normalizeHeadword("IT"));
            assertEquals("india", TextNormalizer.normalizeHeadword("INDIA"));
        }
        finally
        {
            Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("removeDiacritics: strips Vietnamese diacritics for accent-free search")
    void removesVietnameseDiacritics()
    {
        assertEquals("cham soc", TextNormalizer.removeDiacritics("chăm sóc"));
        assertEquals("tieng Viet", TextNormalizer.removeDiacritics("tiếng Việt"));
        assertEquals("hoc sinh gioi", TextNormalizer.removeDiacritics("học sinh giỏi"));
    }

    @Test
    @DisplayName("removeDiacritics handles the d-stroke letter separately, NFD cannot split it")
    void handlesDStrokeSpecially()
    {
        // đ (U+0111) là ký tự độc lập, không phải d + dấu phụ; chỉ dựa vào NFD thì search không dấu hỏng.
        assertEquals("dong", TextNormalizer.removeDiacritics("đông"));
        assertEquals("Dong", TextNormalizer.removeDiacritics("Đông"));
        assertEquals("do do da", TextNormalizer.removeDiacritics("đỏ đỏ đã"));
    }

    @Test
    @DisplayName("removeDiacritics leaves English untouched")
    void leavesAsciiUntouched()
    {
        assertEquals("about to", TextNormalizer.removeDiacritics("about to"));
        assertEquals("give up", TextNormalizer.removeDiacritics("give up"));
    }

    @Test
    @DisplayName("normalizeVietnamese: strips diacritics and lowercases together")
    void normalizesVietnameseQuery()
    {
        assertEquals("cham soc", TextNormalizer.normalizeVietnamese("Chăm Sóc"));
        assertEquals("cham soc", TextNormalizer.normalizeVietnamese("  CHĂM SÓC  "));
    }

    @Test
    @DisplayName("stripBom removes a leading UTF-8 BOM")
    void stripsBom()
    {
        assertEquals("@a /ei/", TextNormalizer.stripBom("﻿@a /ei/"));
        assertEquals("khong co bom", TextNormalizer.stripBom("khong co bom"));
        assertEquals("", TextNormalizer.stripBom(""));
    }

    @Test
    @DisplayName("collapseSpaces merges any whitespace into a single space")
    void collapsesAllWhitespace()
    {
        assertEquals("a b c", TextNormalizer.collapseSpaces("a   b\t\tc"));
        assertEquals("a b", TextNormalizer.collapseSpaces("  a  b  "));
        assertEquals("", TextNormalizer.collapseSpaces("   "));
    }

    @Test
    @DisplayName("Normalization is idempotent: applying it twice gives the same result")
    void normalizationIsIdempotent()
    {
        for (String s : new String[]{"a_la_carte", "  Give  Up ", "chăm sóc", "Đông"})
        {
            String once = TextNormalizer.normalizeHeadword(s);
            assertEquals(once, TextNormalizer.normalizeHeadword(once),
                    "normalizing twice gave a different result for: " + s);
        }
    }

    @Test
    @DisplayName("Vietnamese-only letters are detected, plain English and French loanwords are not")
    void detectsVietnamese()
    {
        assertTrue(TextNormalizer.looksVietnamese("xin chào các bạn"));
        assertTrue(TextNormalizer.looksVietnamese("Hôm nay trời đẹp"));
        assertFalse(TextNormalizer.looksVietnamese("The old system could not keep up"));
        assertFalse(TextNormalizer.looksVietnamese("A café near the résumé desk"));
        assertFalse(TextNormalizer.looksVietnamese(null));
    }
}
