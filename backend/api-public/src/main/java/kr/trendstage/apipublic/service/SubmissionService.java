package kr.trendstage.apipublic.service;

import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.ItemClosedException;
import kr.trendstage.apipublic.web.QuotaExhaustedException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.SubmissionMineResponse;
import kr.trendstage.apipublic.web.SubmissionValidationException;
import kr.trendstage.domain.trend.NameNormalizer;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.SubmissionOrderRankRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 제보 등록/조회. 완전일치(normalized_key) 매칭까지만 여기서 처리한다 — 임베딩 유사도(0.75~0.85
 * 회색지대)는 별도 배치(cluster_merge)가 다룬다(03 §2②·③).
 * 제보권은 QuotaService가 이번 주 제보 행에서 계산해 집행한다(J4). 관측이 끝난 항목에는 제보를 받지 않는다(J6).
 */
@Service
public class SubmissionService {

    private static final Set<Integer> VALID_CONFIDENCE = Set.of(10, 30, 50);

    private final TrendItemRepository trends;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final UserRepository users;
    private final QuotaService quotaService;
    private final Clock clock;

    public SubmissionService(TrendItemRepository trends, SubmissionRepository submissions,
                             SubmissionOrderRankRepository orderRanks, UserRepository users,
                             QuotaService quotaService, Clock clock) {
        this.trends = trends; this.submissions = submissions; this.orderRanks = orderRanks;
        this.users = users; this.quotaService = quotaService; this.clock = clock;
    }

    @Transactional
    public SubmissionMineResponse create(UUID userId, SubmissionCreateRequest req) {
        if (!VALID_CONFIDENCE.contains(req.confidence())) {
            throw new SubmissionValidationException("confidence는 10/30/50 중 하나여야 합니다");
        }
        String normalized = NameNormalizer.normalize(req.name());
        Instant now = clock.instant();

        users.findByIdForUpdate(userId).orElseThrow(() -> new IllegalStateException("유저가 없습니다: " + userId));

        TrendItem item = trends.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            requireOpen(item, now);
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), userId, SubmissionResult.VOID);
            if (dup) {
                long rank = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
                throw new DuplicateSubmissionException((item.getId()), (int) rank,
                        "이미 제보한 항목입니다 — 동의로 전환할 수 있습니다");
            }
        }

        QuotaService.Quota quota = quotaService.of(userId, now);
        if (quota.remaining() == 0) {
            throw new QuotaExhaustedException("이번 주 제보권 %d/%d장을 모두 썼습니다 · 월요일 00:00에 다시 채워집니다"
                    .formatted(quota.used(), quota.max()));
        }

        if (item == null) {
            item = trends.save(new TrendItem(req.name(), normalized, req.category(), now));
            item.transitionTo(TrendState.PENDING);
        }
        Submission sub = submissions.save(new Submission(
                userId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                req.disclosure(), false, now));
        return toResponse(sub, item);
    }

    /** 관측 창(기본 D+14, 유예 연장 반영)이 열려 있는 PENDING 항목만 제보를 받는다(J6). 거부해도 제보권은 쓰지 않는다. */
    private static void requireOpen(TrendItem item, Instant now) {
        if (item.getState() == TrendState.MERGED) {
            // SP2가 병합 승자로 합류시키기 전까지는 막는다 — 죽은 클러스터에 붙어 영원히 PENDING이 되는 것보다 낫다
            throw new ItemClosedException("다른 항목으로 병합된 트렌드입니다 — 검색에서 대표 항목을 찾아 동의해 주세요");
        }
        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
        if (item.getState() != TrendState.PENDING || !now.isBefore(deadline)) {
            throw new ItemClosedException("관측이 끝난 트렌드입니다 — 판정이 끝났거나 진행 중이라 새 제보를 받지 않습니다");
        }
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

        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
        Long judgeInDays = s.getResult() == SubmissionResult.PENDING
                ? Math.max(0, Duration.between(clock.instant(), deadline).toDays())
                : null;

        return new SubmissionMineResponse(
                s.getId(), item.getCanonicalName(), s.getResult().name(),
                null, null, s.getConfidence(), orderRank, null, judgeInDays, s.getCreatedAt());
    }
}
