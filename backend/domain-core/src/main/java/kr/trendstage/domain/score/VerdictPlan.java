package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * 한 항목의 판정 계획(순수 산출물). 원장 라인은 시딩이 아닌 제보만 담는다(P2) — 판정 결과(result)는
 * 호출 측이 유효 제보 전부(시딩 포함)에 적용한다. IO 없음 — 시뮬레이션(ADM-600)이 같은 계산을 쓴다.
 */
public record VerdictPlan(VerdictResult result, ReachLevel reach, double t, List<LedgerLine> ledgerLines) {}
