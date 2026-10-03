package com.anhtuan.dict.core.model;

import java.util.List;

// Một nhóm nghĩa theo từ loại, mở ra bởi dòng '*' (ví dụ '@about' có 2 Sense: phó từ và giới từ).
// pos là null nếu entry không có dòng '*'; glosses lấy từ các dòng '-', examples từ các dòng '='.
public record Sense(String pos, List<String> glosses, List<Example> examples)
{
    public Sense
    {
        glosses = List.copyOf(glosses);
        examples = List.copyOf(examples);
    }

    // Nghĩa đầu tiên, dùng làm gloss mặc định khi dịch câu.
    public String primaryGloss()
    {
        return glosses.isEmpty() ? null : glosses.getFirst();
    }
}
