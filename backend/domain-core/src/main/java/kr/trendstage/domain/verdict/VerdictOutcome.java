package kr.trendstage.domain.verdict;

/** 판정 결과 + 종합점수 T + 확산규모(HIT만). */
public record VerdictOutcome(VerdictResult result, ReachLevel reach, double t) {}
