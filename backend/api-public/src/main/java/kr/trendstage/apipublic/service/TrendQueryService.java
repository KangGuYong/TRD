package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.TrendSummaryResponse;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.StageEvaluator;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MetricSnapshotRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.MetricSource;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 홈/목록 조회. 정렬은 "지금 행동해야 하는 순"(StageEvaluator.actionPriority) — 인기순 아님(앱 원칙 05).
 * daily=true면 상위 5개만("더 보기" 없음, 앱 원칙 01).
 */
@Service
public class TrendQueryService {

    private static final int DAILY_LIMIT = 5;

    /** 채널 표시명(경로 텍스트용). */
    private static final java.util.Map<MetricSource, String> CH = java.util.Map.of(
            MetricSource.DCINSIDE, "디시", MetricSource.X, "X", MetricSource.NAVER_DATALAB, "네이버",
            MetricSource.INSTAGRAM, "인스타", MetricSource.DERIVED, "파생");

    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final MetricSnapshotRepository metrics;

    public TrendQueryService(TrendItemRepository trends, SubmissionRepository submissions,
                             MetricSnapshotRepository metrics) {
        this.trends = trends; this.submissions = submissions; this.metrics = metrics;
    }

    @Transactional(readOnly = true)
    public List<TrendSummaryResponse> home(boolean daily) {
        List<TrendSummaryResponse> all = trends.findByStateIn(List.of(TrendState.PENDING, TrendState.JUDGING))
                .stream()
                .map(this::toSummary)
                .sorted(Comparator.comparingInt(r -> StageEvaluator.actionPriority(DisplayStage.valueOf(r.stage()))))
                .collect(Collectors.toList());
        return daily ? all.stream().limit(DAILY_LIMIT).toList() : all;
    }

    private TrendSummaryResponse toSummary(TrendItem item) {
        List<MetricSource> reached = metrics.findDistinctSources(item.getId());
        int reachedCount = reached.size();
        boolean resolvedFading = item.getState() == TrendState.RESOLVED;   // 잠정 근사
        DisplayStage stage = StageEvaluator.fromReach(reachedCount, resolvedFading);

        String meaning = submissions.findFirstByTrendItemIdOrderByCreatedAtAsc(item.getId())
                .map(Submission::getOneLine).orElse("");

        String pathText = reached.stream().map(s -> CH.getOrDefault(s, s.name())).collect(Collectors.joining(" → "));

        return new TrendSummaryResponse(
                item.getId(),
                item.getCanonicalName(),
                meaning,
                stage.name(),
                stage.label(),
                lifeText(stage),
                pathText,
                reachedCount,
                null);
    }

    private static String lifeText(DisplayStage stage) {
        return switch (stage) {
            case SEED -> "아직 아무도 모릅니다";
            case RISING -> "지금이 적기예요";
            case PEAK -> "곧 흔해져요";
            case FADING -> "지금 쓰면 늦어요";
        };
    }
}
