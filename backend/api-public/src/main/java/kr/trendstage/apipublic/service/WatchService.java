package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.WatchItemResponse;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.NameNormalizer;
import kr.trendstage.domain.trend.StageEvaluator;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Watch;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.WatchRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 워치(관심 키워드 추적) CRUD. 실제 푸시 발송은 스코프 밖(2단계-a 스펙 참고). */
@Service
public class WatchService {

    private final WatchRepository watches;
    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;

    public WatchService(WatchRepository watches, TrendItemRepository trends, SubmissionRepository submissions) {
        this.watches = watches; this.trends = trends; this.submissions = submissions;
    }

    @Transactional
    public void add(UUID userId, String keyword) {
        String normalized = NameNormalizer.normalize(keyword);
        if (!watches.existsByUserIdAndNormalizedKey(userId, normalized)) {
            watches.save(new Watch(userId, keyword, normalized));
        }
    }

    @Transactional
    public void remove(UUID userId, String keyword) {
        watches.deleteByUserIdAndNormalizedKey(userId, NameNormalizer.normalize(keyword));
    }

    @Transactional(readOnly = true)
    public List<WatchItemResponse> list(UUID userId) {
        return watches.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    private WatchItemResponse toResponse(Watch w) {
        TrendItem item = trends.findByNormalizedKey(w.getNormalizedKey()).orElse(null);
        if (item != null && item.getState() == TrendState.MERGED && item.getMergedInto() != null) {
            item = trends.findById(item.getMergedInto()).orElse(item);
        }
        if (item == null) {
            return new WatchItemResponse(w.getKeyword(), null, "아직 관측되지 않음");
        }
        List<String> platforms = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID);
        boolean resolvedFading = item.getState() == TrendState.RESOLVED;
        DisplayStage stage = StageEvaluator.fromReach(platforms.size(), resolvedFading);
        return new WatchItemResponse(w.getKeyword(), stage.name(), null);
    }
}
