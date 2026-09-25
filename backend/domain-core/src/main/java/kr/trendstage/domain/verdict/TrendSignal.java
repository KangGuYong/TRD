package kr.trendstage.domain.verdict;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 판정 입력 = 관측 마감 전에 들어온 비VOID 제보 전부(J6). 외부 지표 없음(R5).
 * 시딩도 담는다 — "유효 제보 0건" 판정과 시딩 결과 기록에 필요하고, 계산이 시딩을 뺀다(P2, J3).
 * SP4: activeSubmitters(상대 목표치 입력, 판정 시점 동결)와 제보별 플랫폼 코드·기기/IP 그룹 번호.
 * 판정 근거(evidence_json)에 그대로 동결된다 — 해시는 담지 않는다(S7). SP4 이전 근거에는 새 값이 없다(null, S8).
 * JSON으로 오가는 레코드라 보조 생성자를 두지 않는다 — 옛 형식은 정적 팩토리로 만든다.
 */
public record TrendSignal(Instant deadline, List<Entry> entries, Integer activeSubmitters) {

    /**
     * @param platform     SP4 이전 자유 텍스트(옛 근거 호환용). 새 판정에서는 null
     * @param platformCode {@link kr.trendstage.domain.signal.Platform} 이름. SP4 이전 근거에는 없다
     * @param deviceGroup  이 판정 안에서만 의미 있는 기기 그룹 번호(1부터). 기기 정보가 없으면 null
     * @param ipGroup      같은 방식의 IP 그룹 번호
     */
    public record Entry(UUID submissionId, UUID userId, boolean seed, Instant submittedAt, String platform,
                        Instant userJoinedAt, String platformCode, Integer deviceGroup, Integer ipGroup) {

        /** SP4 이전 형식(플랫폼 자유 텍스트만). */
        public static Entry legacy(UUID submissionId, UUID userId, boolean seed, Instant submittedAt,
                                   String platform, Instant userJoinedAt) {
            return new Entry(submissionId, userId, seed, submittedAt, platform, userJoinedAt, null, null, null);
        }
    }

    public TrendSignal {
        entries = List.copyOf(entries);
    }

    /** activeSubmitters 없이 — 목표치는 하한이 된다. */
    public static TrendSignal of(Instant deadline, List<Entry> entries) {
        return new TrendSignal(deadline, entries, null);
    }

    /** 유효(비VOID) 제보 수 — 시딩 포함. 0이면 판정은 VOID(P5). */
    public int validCount() {
        return entries.size();
    }

    /** 시딩을 뺀 서로 다른 제보자(계정) 수 — 압축 전. */
    public int distinctSubmitters() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::userId).distinct().count();
    }

    /** 시딩을 뺀 서로 다른 플랫폼 수 — 코드가 있으면 코드, 없으면(SP4 이전) 자유 텍스트로 센다. 표시용. */
    public int distinctPlatforms() {
        return (int) entries.stream().filter(e -> !e.seed())
                .map(e -> e.platformCode() != null ? e.platformCode() : e.platform())
                .filter(Objects::nonNull).distinct().count();
    }
}
