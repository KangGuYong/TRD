package kr.trendstage.domain.verdict;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 판정 입력 = 관측 마감 전에 들어온 비VOID 제보 전부(J6). 외부 지표 없음(R5).
 * 시딩도 담는다 — "유효 제보 0건" 판정과 시딩 결과 기록에 필요하고, 계산 메서드가 시딩을 뺀다(P2, J3).
 * SP4가 쓸 원자료(제보 시각·플랫폼·제보자 가입일)를 지금부터 담아 판정 근거에 동결한다.
 */
public record TrendSignal(Instant deadline, List<Entry> entries) {

    public record Entry(UUID submissionId, UUID userId, boolean seed,
                        Instant submittedAt, String platform, Instant userJoinedAt) {}

    public TrendSignal {
        entries = List.copyOf(entries);
    }

    /** 유효(비VOID) 제보 수 — 시딩 포함. 0이면 판정은 VOID(P5). */
    public int validCount() {
        return entries.size();
    }

    /** 시딩을 뺀 서로 다른 제보자 수 — T의 입력. */
    public int distinctSubmitters() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::userId).distinct().count();
    }

    /** 시딩을 뺀 서로 다른 플랫폼 수(현행 자유 텍스트) — SP4 전까지 판정에 쓰지 않는다. */
    public int distinctPlatforms() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::platform)
                .filter(Objects::nonNull).distinct().count();
    }
}
