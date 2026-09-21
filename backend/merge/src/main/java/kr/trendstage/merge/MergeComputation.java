package kr.trendstage.merge;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 병합 계산 순수 함수 모음. MergeService.merge()(실행)와 preview()(dry-run) 둘 다 재사용한다 —
 * 여기 로직이 바뀌면 실행 결과와 미리보기 결과가 항상 같이 바뀐다(드리프트 불가능).
 * DB·엔티티에 의존하지 않아 Spring 컨텍스트 없이 단위 테스트 가능하다.
 */
public final class MergeComputation {

    private MergeComputation() {}

    public record SubmissionInput(UUID submissionId, UUID userId, String rawInput, Instant createdAt, boolean seed) {}

    /** rank는 시딩이면 null. */
    public record OrderComputed(UUID submissionId, UUID userId, Integer rank, boolean seed) {}

    /** 같은 유저가 두 클러스터 모두에 유효 제보를 낸 경우, 늦은 쪽 submissionId를 반환(03 §4.4 — 헤지 방지). */
    public static Set<UUID> computeDedup(List<SubmissionInput> a, List<SubmissionInput> b) {
        Map<UUID, List<SubmissionInput>> byUser = new HashMap<>();
        for (SubmissionInput s : a) byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);
        for (SubmissionInput s : b) byUser.computeIfAbsent(s.userId(), k -> new ArrayList<>()).add(s);

        Set<UUID> voided = new HashSet<>();
        for (List<SubmissionInput> group : byUser.values()) {
            if (group.size() < 2) continue;
            group.sort(Comparator.comparing(SubmissionInput::createdAt));
            for (int i = 1; i < group.size(); i++) voided.add(group.get(i).submissionId());
        }
        return voided;
    }

    /** 가장 많이 쓰인 raw_input(동률이면 더 이른 것)을 새 대표명으로. 입력이 비었으면 empty(대표명 변경 없음). */
    public static Optional<String> computeCanonicalName(List<SubmissionInput> active) {
        if (active.isEmpty()) return Optional.empty();
        Map<String, List<SubmissionInput>> byRawInput = active.stream()
                .collect(Collectors.groupingBy(SubmissionInput::rawInput));
        String best = null;
        int bestCount = -1;
        Instant bestEarliest = null;
        for (var e : byRawInput.entrySet()) {
            int count = e.getValue().size();
            Instant earliest = e.getValue().stream().map(SubmissionInput::createdAt).min(Instant::compareTo).orElseThrow();
            if (count > bestCount || (count == bestCount && earliest.isBefore(bestEarliest))) {
                best = e.getKey();
                bestCount = count;
                bestEarliest = earliest;
            }
        }
        return Optional.of(best);
    }

    /**
     * 선점 순위 — submission_order_rank 뷰(V28)와 같은 규칙: 시딩 제외, created_at 오름차순,
     * 동시각은 같은 순위(RANK, 1·1·3). 시딩은 순위 없이 뒤에 붙인다(미리보기 표시용).
     */
    public static List<OrderComputed> computeCombinedOrder(List<SubmissionInput> active) {
        List<SubmissionInput> ranked = active.stream()
                .filter(s -> !s.seed())
                .sorted(Comparator.comparing(SubmissionInput::createdAt))
                .toList();
        List<OrderComputed> result = new ArrayList<>();
        int rank = 0;
        Instant previous = null;
        for (int i = 0; i < ranked.size(); i++) {
            SubmissionInput s = ranked.get(i);
            if (previous == null || !s.createdAt().equals(previous)) {
                rank = i + 1;
                previous = s.createdAt();
            }
            result.add(new OrderComputed(s.submissionId(), s.userId(), rank, false));
        }
        active.stream()
                .filter(SubmissionInput::seed)
                .sorted(Comparator.comparing(SubmissionInput::createdAt))
                .forEach(s -> result.add(new OrderComputed(s.submissionId(), s.userId(), null, true)));
        return result;
    }
}
