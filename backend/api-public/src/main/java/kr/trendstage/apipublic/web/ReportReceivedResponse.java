package kr.trendstage.apipublic.web;

import java.time.Instant;
import java.util.UUID;

/** OpenAPI ReportReceived 대응 — 나를 지목해 소명을 요구한 신고. */
public record ReportReceivedResponse(
        UUID id,
        UUID trendItemId,
        String reason,
        String detail,
        Instant explanationDeadline,
        boolean explanationSubmitted,
        String status
) {}
