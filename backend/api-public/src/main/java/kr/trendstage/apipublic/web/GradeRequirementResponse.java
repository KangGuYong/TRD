package kr.trendstage.apipublic.web;

public record GradeRequirementResponse(String label, double current, double required, boolean met, String basis) {}
