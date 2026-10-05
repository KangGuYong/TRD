package kr.trendstage.domain.backtest;

import kr.trendstage.domain.signal.TBreakdown;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * 현재 운영값 vs 초안값 비교(SP4 §5.4). HIT가 양성. 분모가 0인 비율은 null(화면 "—").
 * reachAgreement는 정답 등급이 있고 정답·예측이 모두 HIT인 사례 중 등급이 같은 비율.
 */
public record BacktestReport(int caseCount, Side current, Side draft, int changedCount, List<CaseRow> rows) {

    public record Confusion(int tp, int fp, int fn, int tn) {}

    public record Side(Confusion confusion, Double precision, Double recall, Double reachAgreement, int reachCompared) {}

    public record Outcome(VerdictResult result, ReachLevel reach, TBreakdown breakdown, String explain) {}

    public record CaseRow(String caseId, String title, VerdictResult label, ReachLevel labelReach,
                          Outcome current, Outcome draft, boolean changed) {}
}
