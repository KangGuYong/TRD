package kr.trendstage.domain.grade;

import java.util.List;
import java.util.Locale;

/**
 * 등급 산정 정책. 승급은 활동점수(AS)와 신뢰도(TI)를 <b>모두(AND)</b> 요구한다(R3).
 * 제보 건수 단독 승급 경로는 타입상 존재하지 않는다. 01 §6.
 *
 * <pre>
 *   L1: 판정완료 ≥ 5,  TI ≥ 0.35, AS ≥ 30
 *   L2: 판정완료 ≥ 15, TI ≥ 0.45, AS ≥ 150
 *   L3: 판정완료 ≥ 40, TI ≥ 0.55, AS ≥ 500
 *   L4: L3 + 상위 1% (정원제) — 이 엔진 밖(l4_quota 배치)에서 결정
 * </pre>
 *
 * L4 정원 판정은 순수 함수로 표현할 수 없으므로(전체 분포 필요) 여기서는 L3까지만 산출한다.
 */
public final class GradePolicy {
    private GradePolicy() {}

    private record Req(Grade grade, int minJudged, double minTi, double minAs) {}

    private static final List<Req> LADDER = List.of(
            new Req(Grade.L1, 5,  0.35, 30),
            new Req(Grade.L2, 15, 0.45, 150),
            new Req(Grade.L3, 40, 0.55, 500)
    );

    /** 조건을 모두 만족하는 가장 높은 등급 + 다음 등급 요구 항목을 반환. */
    public static GradeStatus evaluate(int judgedCount, double trustIndex, double activeScore) {
        Grade current = Grade.L0;
        for (Req r : LADDER) {
            if (judgedCount >= r.minJudged && trustIndex >= r.minTi && activeScore >= r.minAs) {
                current = r.grade;
            } else {
                break; // 사다리는 순차 — 한 칸 막히면 위도 막힘
            }
        }
        Grade next = nextOf(current);
        return new GradeStatus(current, next, progressToward(next, judgedCount, trustIndex, activeScore));
    }

    /** 다음 등급. L3·L4 다음은 L4(L4는 정원제 — 사다리 밖). */
    public static Grade nextOf(Grade g) {
        return switch (g) {
            case L0 -> Grade.L1; case L1 -> Grade.L2; case L2 -> Grade.L3;
            case L3, L4 -> Grade.L4;
        };
    }

    /**
     * 목표 등급의 요구 항목과 충족 여부. 공식 등급은 주간 스냅샷이고 진행 상황만 실시간으로 보여줄 때 쓴다(J5).
     * 사다리에 없는 등급(L4)이면 빈 목록.
     */
    public static List<GradeRequirement> progressToward(Grade target, int judgedCount, double trustIndex, double activeScore) {
        Req r = LADDER.stream().filter(x -> x.grade == target).findFirst().orElse(null);
        if (r == null) return List.of();
        return List.of(
                req(GradeRequirementKind.JUDGED_COUNT, "판정 완료", judgedCount, r.minJudged, 0),
                req(GradeRequirementKind.TRUST_INDEX, "신뢰도 지수 TI", trustIndex, r.minTi, 2),
                req(GradeRequirementKind.ACTIVE_SCORE, "활동 점수 AS", activeScore, r.minAs, 0));
    }

    private static GradeRequirement req(GradeRequirementKind kind, String label, double cur, double required, int decimals) {
        boolean met = cur >= required;
        double gap = Math.max(0, required - cur);
        String fmt = "%." + decimals + "f";
        String basis = met
                ? String.format(Locale.US, label + " " + fmt + " / 요구치 " + fmt + " · 충족", cur, required)
                : String.format(Locale.US, label + " " + fmt + " / 요구치 " + fmt + " / 부족분 " + fmt, cur, required, gap);
        return new GradeRequirement(kind, label, cur, required, met, basis);
    }
}
