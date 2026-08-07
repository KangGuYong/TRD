package kr.trendstage.domain.score;

import java.util.UUID;

/**
 * 판정 시점에 동결된 제보 스냅샷. orderRank는 뷰에서 읽어 이 시점에 고정한다(03 §3.1).
 * VOID 제보는 애초에 목록에서 제외하고 넘긴다.
 */
public record SubmissionRef(UUID submissionId, UUID userId, int confidence, int orderRank, long elapsedDays) {}
