package kr.trendstage.domain.grade;

import java.util.List;

/** 현재 등급 + 다음 등급까지의 요구 항목(부족분 포함). */
public record GradeStatus(Grade current, Grade next, List<GradeRequirement> requirements) {}
