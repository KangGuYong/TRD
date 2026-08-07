package kr.trendstage.domain.score;

/**
 * 점수 계산 결과. delta와 함께 <b>산정 근거(breakdown)</b>를 항상 동봉한다.
 * 관리자 API·유저 원장 응답이 이 문자열을 그대로 노출한다(04 §7.3).
 * 예: "HIT L3 · 확신도 30 × 선점 1위 × 확산 ×2.0 × 감쇠 1.00 = +60.0"
 */
public record ScoreResult(double delta, String breakdown) {}
