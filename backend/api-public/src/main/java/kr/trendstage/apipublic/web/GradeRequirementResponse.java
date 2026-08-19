package kr.trendstage.apipublic.web;

import kr.trendstage.domain.grade.GradeRequirementKind;

public record GradeRequirementResponse(GradeRequirementKind kind, String label, double current, double required, boolean met, String basis) {}
