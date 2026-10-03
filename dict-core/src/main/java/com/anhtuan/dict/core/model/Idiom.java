package com.anhtuan.dict.core.model;

import java.util.List;

// Thành ngữ / cụm từ cố định, mở ra bởi dòng '!' trong file nguồn. Bẫy lớn nhất của parser: các dòng '-' đứng sau '!'
// thuộc về Idiom này chứ không phải Sense đang mở. Idiom cũng có thể có ví dụ '=' riêng ('!to be about to' trong '@about').
public record Idiom(String phrase, List<String> glosses, List<Example> examples)
{
    public Idiom
    {
        if (phrase == null)
            throw new IllegalArgumentException("phrase must not be null");
        glosses = List.copyOf(glosses);
        examples = List.copyOf(examples);
    }
}
