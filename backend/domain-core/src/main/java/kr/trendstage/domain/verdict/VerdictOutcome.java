package kr.trendstage.domain.verdict;

import kr.trendstage.domain.signal.TBreakdown;

/** 판정 결과 + 유효 T + 확산규모(HIT만) + T 분해. */
public record VerdictOutcome(VerdictResult result, ReachLevel reach, double t, TBreakdown breakdown) {}
