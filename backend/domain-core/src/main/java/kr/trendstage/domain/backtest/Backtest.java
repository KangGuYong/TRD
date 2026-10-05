package kr.trendstage.domain.backtest;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 백테스트(ADM-600, SP4 S9) 순수 함수. 운영 판정과 같은 VerdictEngine으로 사례마다 현재값·초안값을 계산해
 * 정답과 비교한다 — 드리프트 방지(운영/시뮬레이션/백테스트가 같은 엔진).
 */
public final class Backtest {
    private Backtest() {}

    public static BacktestReport run(List<BacktestCase> cases, ParameterSet current, ParameterSet draft) {
        List<BacktestReport.CaseRow> rows = new ArrayList<>();
        for (BacktestCase c : cases) {
            BacktestReport.Outcome now = outcome(c.signal(), current);
            BacktestReport.Outcome next = outcome(c.signal(), draft);
            boolean changed = now.result() != next.result() || now.reach() != next.reach();
            rows.add(new BacktestReport.CaseRow(c.caseId(), c.title(), c.label(), c.labelReach(), now, next, changed));
        }
        int changedCount = (int) rows.stream().filter(BacktestReport.CaseRow::changed).count();
        return new BacktestReport(cases.size(), side(rows, true), side(rows, false), changedCount, rows);
    }

    private static BacktestReport.Outcome outcome(TrendSignal signal, ParameterSet p) {
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new BacktestReport.Outcome(o.result(), o.reach(), o.breakdown(), o.breakdown().describe());
    }

    private static BacktestReport.Side side(List<BacktestReport.CaseRow> rows, boolean current) {
        int tp = 0, fp = 0, fn = 0, tn = 0, compared = 0, agreed = 0;
        for (BacktestReport.CaseRow r : rows) {
            BacktestReport.Outcome o = current ? r.current() : r.draft();
            boolean predicted = o.result() == VerdictResult.HIT;
            boolean actual = r.label() == VerdictResult.HIT;
            if (predicted && actual) tp++;
            else if (predicted) fp++;
            else if (actual) fn++;
            else tn++;
            if (r.labelReach() != null && predicted && actual) {
                compared++;
                if (o.reach() == r.labelReach()) agreed++;
            }
        }
        return new BacktestReport.Side(new BacktestReport.Confusion(tp, fp, fn, tn),
                ratio(tp, tp + fp), ratio(tp, tp + fn), ratio(agreed, compared), compared);
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : numerator / (double) denominator;
    }
}
