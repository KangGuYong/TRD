package kr.trendstage.apipublic.web;

import java.util.UUID;

/**
 * 홈 카드 / 목록 응답. OpenAPI TrendSummary 스키마와 대응(backend/api-spec/openapi.yaml).
 * 미보유 콘텐츠(연령대 등)는 null — 스키마상 optional.
 */
public record TrendSummaryResponse(
        UUID id,
        String word,
        String meaning,
        String stage,        // SEED|RISING|PEAK|FADING
        String stageLabel,   // 씨앗|급상승|정점|식는 중
        String lifeText,     // "숫자보다 문장"
        String pathText,     // "디시 → X → 인스타"
        int reachedCount,
        String ageShort      // 미보유 시 null
) {}
