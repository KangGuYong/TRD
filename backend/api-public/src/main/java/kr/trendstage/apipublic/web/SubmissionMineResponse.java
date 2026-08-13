package kr.trendstage.apipublic.web;

import java.time.Instant;
import java.util.UUID;

/** OpenAPI SubmissionMine 대응. */
public record SubmissionMineResponse(
        UUID id,
        String word,
        String status,        // PENDING|HIT|MISS|VOID
        String reachLevel,    // null 가능
        Double delta,
        int confidence,
        Integer orderRank,    // 판정 전에는 파생 잠정값
        String note,          // 산정 근거
        Long judgeInDays,
        Instant createdAt
) {}
