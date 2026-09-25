package kr.trendstage.domain.verdict;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.Independence;
import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.domain.signal.TBreakdown;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 유효 T와 판정 결과를 산출하는 순수 함수(SP4 §2).
 *
 * <pre>
 *   independent = 독립 제보자 수(시딩 제외, independenceMode로 압축)
 *   target      = max(targetFloor, ⌈activeSubmitters × targetRatio⌉)   — activeSubmitters 없으면 targetFloor
 *   ratio       = clip(independent / target, 0, 1)
 *   persistence = pFloor + (1 − pFloor) × min(1, 제보일수(KST) / pFullDays)
 *   diversity   = dFloor + (1 − dFloor) × min(1, (플랫폼 수 − 1) / (dFullPlatforms − 1))   — 코드 없는 제보가 섞이면 1
 *   T = ratio × persistence × diversity
 *   T < hitThreshold → MISS, 이후 bandL2/L3/L4로 L1~L4
 * </pre>
 *
 * 기본값(SignalAxes.NEUTRAL)에서는 persistence = diversity = 1.0, target = targetFloor라 SP4 이전 공식과 같다(S6).
 * 값은 근거 없는 초기 추정치다 — 백테스트(ADM-600)를 거쳐 2인 승인으로 바꾼다(O1·O8).
 * 시딩은 T에서 빠진다(J3). VOID는 엔진 밖의 사건이고, 판정 시점 VOID는 유효 제보 0건뿐이다(P5).
 */
public final class VerdictEngine {
    private VerdictEngine() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** ⌈활성 × 비율⌉의 부동소수 잡음 제거(60 × 0.1 = 6.000000000000001). */
    private static final double CEIL_EPSILON = 1e-9;

    public static double computeT(TrendSignal sig, ParameterSet p) {
        return breakdown(sig, p).t();
    }

    public static VerdictOutcome evaluate(TrendSignal sig, ParameterSet p) {
        return classify(breakdown(sig, p), p);
    }

    public static TBreakdown breakdown(TrendSignal sig, ParameterSet p) {
        SignalAxes a = p.axes;
        List<TrendSignal.Entry> real = sig.entries().stream().filter(e -> !e.seed()).toList();

        int accounts = (int) real.stream().map(TrendSignal.Entry::userId).distinct().count();
        int independent = Independence.count(real, a.independenceMode());
        Integer active = sig.activeSubmitters();
        int target = active == null ? p.targetFloor
                : Math.max(p.targetFloor, (int) Math.ceil(active * a.targetRatio() - CEIL_EPSILON));
        double ratio = Math.max(0.0, Math.min(1.0, independent / (double) target));

        int activeDays = (int) real.stream().map(e -> LocalDate.ofInstant(e.submittedAt(), KST)).distinct().count();
        double persistence = a.persistenceFloor()
                + (1 - a.persistenceFloor()) * Math.min(1.0, activeDays / (double) a.persistenceFullDays());

        boolean coded = !real.isEmpty() && real.stream().allMatch(e -> e.platformCode() != null);
        int platforms = coded ? (int) real.stream().map(TrendSignal.Entry::platformCode).distinct().count() : 0;
        double diversity = coded
                ? a.diversityFloor() + (1 - a.diversityFloor())
                        * Math.min(1.0, (platforms - 1) / (double) (a.diversityFullPlatforms() - 1))
                : 1.0;

        double t = ratio * persistence * diversity;
        return new TBreakdown(accounts, independent, target, active, ratio, activeDays, a.persistenceFullDays(),
                persistence, platforms, a.diversityFullPlatforms(), coded, diversity, t);
    }

    private static VerdictOutcome classify(TBreakdown b, ParameterSet p) {
        double t = b.t();
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t, b);
        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;
        return new VerdictOutcome(VerdictResult.HIT, reach, t, b);
    }
}
