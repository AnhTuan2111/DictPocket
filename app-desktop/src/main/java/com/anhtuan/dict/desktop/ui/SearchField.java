package com.anhtuan.dict.desktop.ui;

import javafx.scene.control.TextField;

// Ô nhập một dòng. TextField của JavaFX xoá hết dấu xuống dòng khi dán, nên văn bản ngắt dòng cứng
// bị dính chữ cuối dòng với chữ đầu dòng sau ("the" + "result" thành "theresult") và dịch sai.
// Đổi mỗi chỗ xuống dòng thành một dấu cách trước khi chèn.
final class SearchField extends TextField
{
    @Override
    public void replaceText(int start, int end, String text)
    {
        super.replaceText(start, end, text == null ? null : text.replaceAll("\\s*[\\r\\n]+\\s*", " "));
    }
}
