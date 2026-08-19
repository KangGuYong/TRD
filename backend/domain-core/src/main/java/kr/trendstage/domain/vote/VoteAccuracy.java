package kr.trendstage.domain.vote;

import java.util.List;

/**
 * 유저의 "뜬다/안 뜬다" 투표가 실제 판정(HIT/MISS)과 맞았는지 집계.
 * VOID·미판정 항목은 호출자가 미리 걸러서 넘긴다(이 함수는 판정 난 것만 안다는 전제).
 */
public final class VoteAccuracy {
    private VoteAccuracy() {}

    public record VoteOutcome(boolean willTrend, boolean actualHit) {}
    public record Result(int total, int correct, double hitRate) {}

    public static Result compute(List<VoteOutcome> outcomes) {
        int total = outcomes.size();
        int correct = 0;
        for (VoteOutcome o : outcomes) {
            if (o.willTrend() == o.actualHit()) correct++;
        }
        double hitRate = total == 0 ? 0.0 : (double) correct / total;
        return new Result(total, correct, hitRate);
    }
}
