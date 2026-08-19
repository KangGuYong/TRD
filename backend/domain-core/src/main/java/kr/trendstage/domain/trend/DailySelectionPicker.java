package kr.trendstage.domain.trend;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "오늘의 5개" 선정 순서 계산. 순수 함수 — DB/시간 의존 없음.
 * 선호 카테고리 일치 항목 우선, 그 안에서는 actionPriority(낮을수록 먼저) 순.
 */
public final class DailySelectionPicker {
    private DailySelectionPicker() {}

    public record Candidate(UUID trendItemId, String category, int actionPriority) {}

    public static List<UUID> pick(List<Candidate> candidates, Set<String> preferredCategories, int limit) {
        Comparator<Candidate> byMatchThenPriority = Comparator
                .comparingInt((Candidate c) -> preferredCategories.contains(c.category()) ? 0 : 1)
                .thenComparingInt(Candidate::actionPriority);

        return candidates.stream()
                .sorted(byMatchThenPriority)
                .map(Candidate::trendItemId)
                .limit(limit)
                .toList();
    }
}
