package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.TrendSummaryResponse;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.StageEvaluator;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
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

    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;

    public TrendQueryService(TrendItemRepository trends, SubmissionRepository submissions) {
        this.trends = trends; this.submissions = submissions;
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
        List<String> platforms = submissions.findDistinctPlatforms(item.getId());
        int reachedCount = platforms.size();
        boolean resolvedFading = item.getState() == TrendState.RESOLVED;   // 잠정 근사
        DisplayStage stage = StageEvaluator.fromReach(reachedCount, resolvedFading);

        String meaning = submissions.findFirstByTrendItemIdOrderByCreatedAtAsc(item.getId())
                .map(Submission::getOneLine).orElse("");

        String pathText = String.join(" → ", platforms);

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
