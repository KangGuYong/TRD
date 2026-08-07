package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

/**
 * 건별 점수 계산 입력.
 *
 * @param result     판정 결과
 * @param confidence 확신도 c (10/30/50)
 * @param orderRank  선점 순위 (판정 시점 동결값, 03 §3.1)
 * @param reach      확산 규모 (HIT만 유효, MISS/VOID는 null 허용)
 * @param elapsedDays 제보 시각 기준 경과일수 (시간감쇠 d)
 */
public record ScoreInput(VerdictResult result, int confidence, int orderRank,
                         ReachLevel reach, long elapsedDays) {}
