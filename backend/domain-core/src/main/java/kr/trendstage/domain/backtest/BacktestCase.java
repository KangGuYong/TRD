package kr.trendstage.domain.backtest;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;

/**
 * 백테스트 사례 한 건. label·labelReach는 사람이 사후에 붙인 정답 — 평가에만 쓰고 판정 입력이 아니다(SP4 S10).
 * 판정 입력은 signal(사례의 제보 시계열)뿐이다.
 */
public record BacktestCase(String caseId, String title, VerdictResult label, ReachLevel labelReach, TrendSignal signal) {}
