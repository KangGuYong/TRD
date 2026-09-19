package kr.trendstage.domain.score;

import java.util.UUID;

/**
 * 판정 시점에 동결된 제보 스냅샷. orderRank는 시딩을 뺀 순위(J3, 뷰에서 읽어 이 시점에 고정 — 03 §3.1).
 * 시딩 제보는 seed=true — 결과는 매기되 원장 라인은 만들지 않는다(P2). VOID 제보는 애초에 넘기지 않는다.
 */
public record SubmissionRef(UUID submissionId, UUID userId, int confidence, int orderRank, boolean seed) {}
