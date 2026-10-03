package com.anhtuan.dict.core.model;

// Một ví dụ minh họa từ dòng "=english+ tiếng việt". vi là null nếu dòng nguồn không có dấu '+'.
// glossIndex là chỉ số gloss mà ví dụ minh họa, -1 nếu ví dụ đứng trước mọi dòng '-'.
public record Example(String en, String vi, int glossIndex)
{
    public Example
    {
        if (en == null)
            throw new IllegalArgumentException("en must not be null");
    }

    public boolean hasTranslation()
    {
        return vi != null && !vi.isBlank();
    }
}
