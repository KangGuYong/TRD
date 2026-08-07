package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * 한 항목의 판정 계획(순수 산출물). 스케줄러는 이걸 그대로 verdicts + score_ledger로 영속화한다.
 * 여기엔 IO가 없다 — 같은 계산을 시뮬레이션(ADM-600)이 재사용한다.
 */
public record VerdictPlan(VerdictResult result, ReachLevel reach, double t, List<LedgerLine> lines) {}
