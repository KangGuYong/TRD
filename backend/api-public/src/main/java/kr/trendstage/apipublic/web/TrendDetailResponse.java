package kr.trendstage.apipublic.web;

import java.util.List;
import java.util.UUID;

/** OpenAPI TrendDetail(TrendSummary + 확장 필드) 대응. */
public record TrendDetailResponse(
        UUID id,
        String word,
        String meaning,
        String stage,
        String stageLabel,
        String lifeText,
        String pathText,
        int reachedCount,
        String ageShort,
        String verdict,          // 판정 전(PENDING/JUDGING)이면 null — 조기 판정 노출 금지
        String verdictWhy,
        String reachLevel,       // HIT일 때만, 그 외 null
        String origin,           // 데이터 없음 — 항상 null
        String example,          // 데이터 없음 — 항상 null
        String ageSentence,      // 데이터 없음 — 항상 null
        List<String> ages,       // 데이터 없음 — 항상 빈 리스트
        List<PropagationStepResponse> propagationPath,
        String voteCount,        // 투표 0건이면 null
        boolean watched          // 워치 테이블 없음 — 항상 false(2단계에서 교체)
) {}
