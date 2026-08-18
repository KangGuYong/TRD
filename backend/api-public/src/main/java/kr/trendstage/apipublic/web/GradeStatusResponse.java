package kr.trendstage.apipublic.web;

import java.util.List;

public record GradeStatusResponse(
        String grade, String gradeName, double trustIndex, double activeScore,
        int judgedCount, String nextGrade, List<GradeRequirementResponse> requirements, String note
) {}
