package kr.trendstage.apipublic.web;

import java.time.Instant;
import java.util.UUID;

/** OpenAPI ReportMine 대응 — 내가 접수한 신고. */
public record ReportMineResponse(
        UUID id,
        UUID trendItemId,
        String status,        // OPEN|EXPLAINING|DECIDED
        String reason,
        String decision,      // null 가능
        String decisionNote,  // null 가능
        Instant createdAt,
        Instant decidedAt     // null 가능
) {}
