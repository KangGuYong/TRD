package kr.trendstage.domain.grade;

/**
 * 승급 요구 항목 하나와 그 충족 여부.
 * {@code basis}는 산정 근거 문자열(예 "TI 0.48 / 요구치 0.55 / 부족분 0.07").
 * 앱 GET /v1/me/grade 응답에 그대로 노출된다(04 §7.3).
 */
public record GradeRequirement(String label, double current, double required, boolean met, String basis) {}
