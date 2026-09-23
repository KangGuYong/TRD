package kr.trendstage.domain.params;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;

/** 과거 판정 1건의 재평가 입력 — 판정 근거(evidence_json)에 동결된 신호. */
public record VerdictSnapshot(VerdictResult result, ReachLevel reach, TrendSignal signal) {}
