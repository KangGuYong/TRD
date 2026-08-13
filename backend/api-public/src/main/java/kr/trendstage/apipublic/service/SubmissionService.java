package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.SubmissionMineResponse;
import kr.trendstage.apipublic.web.SubmissionValidationException;
import kr.trendstage.domain.trend.NameNormalizer;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionOrderRankRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 제보 등록/조회. 완전일치(normalized_key) 매칭까지만 여기서 처리한다 — 임베딩 유사도(0.75~0.85
 * 회색지대)는 별도 배치(cluster_merge, 미구현)가 다룬다(03 §2②·③).
 * 제보권(quota) 차감은 QuotaService 도입 후 처리(TODO, VerdictRunner와 동일 상태).
 */
@Service
public class SubmissionService {

    private static final Set<Integer> VALID_CONFIDENCE = Set.of(10, 30, 50);
    private static final int JUDGE_WINDOW_DAYS = 14;

    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;

    public SubmissionService(TrendItemRepository trends, SubmissionRepository submissions,
                             SubmissionOrderRankRepository orderRanks) {
        this.trends = trends; this.submissions = submissions; this.orderRanks = orderRanks;
    }

    @Transactional
    public SubmissionMineResponse create(UUID userId, SubmissionCreateRequest req) {
        if (!VALID_CONFIDENCE.contains(req.confidence())) {
            throw new SubmissionValidationException("confidence는 10/30/50 중 하나여야 합니다");
        }

        String normalized = NameNormalizer.normalize(req.name());
        Instant now = Instant.now();

        TrendItem item = trends.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), userId, SubmissionResult.VOID);
            if (dup) {
                long rank = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
                throw new DuplicateSubmissionException((item.getId()), (int) rank,
                        "이미 제보한 항목입니다 — 동의로 전환할 수 있습니다");
            }
        } else {
            item = trends.save(new TrendItem(req.name(), normalized, req.category(), now));
            item.transitionTo(TrendState.PENDING);
        }

        Submission sub = submissions.save(new Submission(
                userId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                req.disclosure(), false));

        return toResponse(sub, item);
    }

    @Transactional(readOnly = true)
    public List<SubmissionMineResponse> mine(UUID userId) {
        return submissions.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(s -> toResponse(s, trends.findById(s.getTrendItemId()).orElseThrow()))
                .toList();
    }

    private SubmissionMineResponse toResponse(Submission s, TrendItem item) {
        Integer orderRank = orderRanks.findByTrendItemId(item.getId()).stream()
                .filter(r -> r.getSubmissionId().equals(s.getId()))
                .map(r -> r.getOrderRank())
                .findFirst().orElse(null);

        Long judgeInDays = s.getResult() == SubmissionResult.PENDING
                ? Math.max(0, Duration.between(Instant.now(),
                        item.getFirstSeenAt().plus(Duration.ofDays(JUDGE_WINDOW_DAYS))).toDays())
                : null;

        return new SubmissionMineResponse(
                s.getId(), item.getCanonicalName(), s.getResult().name(),
                null, null, s.getConfidence(), orderRank, null, judgeInDays, s.getCreatedAt());
    }
}
