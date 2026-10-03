package com.anhtuan.dict.core.model;

// Một lựa chọn nghĩa cho một Segment; UI hiện candidate đầu tiên, người dùng bấm để đổi sang candidate khác.
// score là điểm xếp hạng: gloss engine dùng thứ tự sense, BM25 dùng điểm thực.
public record Candidate(String headword, String gloss, String pos, double score, int sourceId)
{

    // Nguồn không xác định, dùng cho candidate tự sinh (từ chức năng, câu đã dịch).
    public static final int NO_SOURCE = -1;

    public Candidate(String headword, String gloss, String pos, double score)
    {
        this(headword, gloss, pos, score, NO_SOURCE);
    }
}
