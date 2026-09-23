package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.TrendNotFoundException;
import kr.trendstage.apipublic.web.WatchItemResponse;
import kr.trendstage.domain.trend.DisplayStage;
import kr.trendstage.domain.trend.NameNormalizer;
import kr.trendstage.domain.trend.StageEvaluator;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.Watch;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.WatchRepository;
import kr.trendstage.persistence.trend.TrendItemLookup;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final TrendItemLookup lookup;

    public WatchService(WatchRepository watches, TrendItemRepository trends, SubmissionRepository submissions,
                        TrendItemLookup lookup) {
        this.watches = watches; this.trends = trends; this.submissions = submissions; this.lookup = lookup;
    }

    @Transactional
    public void add(UUID userId, String keyword) {
        try {
            watches.insertIfAbsent(userId, keyword, NameNormalizer.normalize(keyword));
        } catch (DataIntegrityViolationException e) {
            throw new TrendNotFoundException("존재하지 않는 트렌드 키워드입니다");
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
        // 병합 체인을 끝까지 따라간다(한 단계만 보면 중간 tombstone에서 멈춘다)
        TrendItem item = lookup.findLiveByNormalizedKey(w.getNormalizedKey()).orElse(null);
        if (item == null) {
            return new WatchItemResponse(w.getKeyword(), null, "아직 관측되지 않음");
        }
        List<String> platforms = submissions.findDistinctPlatforms(item.getId(), SubmissionResult.VOID);
        boolean resolvedFading = item.getState() == TrendState.RESOLVED;
        DisplayStage stage = StageEvaluator.fromReach(platforms.size(), resolvedFading);
        return new WatchItemResponse(w.getKeyword(), stage.name(), null);
    }
}
