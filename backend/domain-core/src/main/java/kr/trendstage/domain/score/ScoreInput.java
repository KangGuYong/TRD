package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

/**
 * 건별 점수 계산 입력. 감쇠 없음 — 원장에는 원값을 기록하고 감쇠는 AS 조회 때 적용한다(J1).
 *
 * @param confidence 확신도 c (10/30/50)
 * @param orderRank  선점 순위(시딩 제외, 판정 시점 동결값 — 03 §3.1)
 * @param reach      확산 규모(HIT만 유효, MISS/VOID는 null 허용)
 */
public record ScoreInput(VerdictResult result, int confidence, int orderRank, ReachLevel reach) {}
