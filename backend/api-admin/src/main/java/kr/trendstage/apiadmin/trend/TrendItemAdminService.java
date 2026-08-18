package kr.trendstage.apiadmin.trend;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.SubmissionRef;
import kr.trendstage.domain.score.VerdictComputation;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.domain.verdict.SubmissionSignal;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.SubmissionOrderRank;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.repo.EndorsementRepository;
import kr.trendstage.persistence.repo.SubmissionOrderRankRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserGradeRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-110/111. 목록은 전체 상태 조회, 상세는 VerdictComputation을 드라이런으로 돌려
 * 저장 없이 예상 판정만 계산한다(VerdictAdminService.applySupersede()와 동일한 조립 방식,
 * 다만 결과를 저장하지 않는다는 점만 다르다).
 */
@Service
public class TrendItemAdminService {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final SubmissionOrderRankRepository orderRanks;
    private final VerdictRepository verdicts;
    private final UserRepository users;
    private final UserGradeRepository userGrades;
    private final EndorsementRepository endorsements;
    private final Clock clock;

    public TrendItemAdminService(TrendItemRepository trendItems, SubmissionRepository submissions,
                                  SubmissionOrderRankRepository orderRanks, VerdictRepository verdicts,
                                  UserRepository users, UserGradeRepository userGrades,
                                  EndorsementRepository endorsements, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.verdicts = verdicts;
        this.users = users;
        this.userGrades = userGrades;
        this.endorsements = endorsements;
        this.clock = clock;
    }

    public record TrendItemSummary(String id, String canonicalName, String category, String state,
                                    String firstSeenAt, int submitterCount, String currentResult) {}

    public record SubmissionRow(String submissionId, String userHandle, int orderRank,
                                 int confidence, Double submitterTi, String createdAt,
                                 String platform, String oneLine, String evidenceUrl) {}

    public record TrendItemDetail(
            String id, String canonicalName, String category, String state,
            String firstSeenAt, String deadline, long daysLeft, boolean graceExtended,
            int distinctSubmitters, int distinctPlatforms, long endorseCount,
            String currentResult, String currentReachLevel, String currentScoreT, String currentJudgedAt,
            String previewResult, String previewReachLevel, String previewScoreT,
            List<SubmissionRow> submissions) {}

    @Transactional(readOnly = true)
    public List<TrendItemSummary> list() {
        return trendItems.findAll().stream()
                .sorted(Comparator.comparing(TrendItem::getFirstSeenAt).reversed())
                .map(item -> {
                    long submitterCount = submissions.countByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID);
                    Verdict current = verdicts.findCurrentByTrendItemId(item.getId()).orElse(null);
                    return new TrendItemSummary(
                            item.getId().toString(), item.getCanonicalName(), item.getCategory().name(), item.getState().name(),
                            DISPLAY_FORMAT.format(item.getFirstSeenAt()), (int) submitterCount,
                            current == null ? null : current.getResult().name());
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public TrendItemDetail detail(UUID trendItemId) {
        TrendItem item = trendItems.findById(trendItemId)
                .orElseThrow(() -> new AdminValidationException("존재하지 않는 항목입니다"));

        List<SubmissionOrderRank> ranks = orderRanks.findByTrendItemId(trendItemId);
        Map<UUID, Integer> rankById = ranks.stream()
                .collect(Collectors.toMap(SubmissionOrderRank::getSubmissionId, SubmissionOrderRank::getOrderRank));
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(trendItemId, SubmissionResult.VOID);

        Instant now = clock.instant();
        List<SubmissionRef> refs = subs.stream().map(s -> new SubmissionRef(
                s.getId(), s.getUserId(), s.getConfidence(),
                rankById.getOrDefault(s.getId(), Integer.MAX_VALUE),
                Duration.between(s.getCreatedAt(), now).toDays())).toList();
        long distinctSubmitters = subs.stream().map(Submission::getUserId).distinct().count();
        long distinctPlatforms = subs.stream().map(Submission::getSourcePlatform)
                .filter(Objects::nonNull).distinct().count();
        SubmissionSignal signal = new SubmissionSignal((int) distinctSubmitters, (int) distinctPlatforms);

        Verdict current = verdicts.findCurrentByTrendItemId(trendItemId).orElse(null);
        VerdictPlan preview = current == null
                ? VerdictComputation.run(signal, false, refs, ParameterSet.defaults())
                : null;

        List<SubmissionRow> rows = subs.stream()
                .sorted(Comparator.comparingInt(s -> rankById.getOrDefault(s.getId(), Integer.MAX_VALUE)))
                .map(s -> new SubmissionRow(
                        s.getId().toString(),
                        users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("-"),
                        rankById.getOrDefault(s.getId(), Integer.MAX_VALUE), s.getConfidence(),
                        userGrades.findTopByUserIdOrderByComputedAtDesc(s.getUserId())
                                .map(g -> g.getTrustIndex().doubleValue()).orElse(null),
                        DISPLAY_FORMAT.format(s.getCreatedAt()),
                        s.getSourcePlatform(), s.getOneLine(), s.getEvidenceUrl()))
                .toList();

        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());

        return new TrendItemDetail(
                item.getId().toString(), item.getCanonicalName(), item.getCategory().name(), item.getState().name(),
                DISPLAY_FORMAT.format(item.getFirstSeenAt()), DISPLAY_FORMAT.format(deadline),
                Duration.between(now, deadline).toDays(), item.getJudgmentDeadlineOverride() != null,
                (int) distinctSubmitters, (int) distinctPlatforms, endorsements.countByTrendItemId(trendItemId),
                current == null ? null : current.getResult().name(),
                current == null || current.getReachLevel() == null ? null : current.getReachLevel().name(),
                current == null || current.getScoreT() == null ? null : current.getScoreT().toPlainString(),
                current == null ? null : DISPLAY_FORMAT.format(current.getJudgedAt()),
                preview == null ? null : preview.result().name(),
                preview == null || preview.reach() == null ? null : preview.reach().name(),
                preview == null ? null : BigDecimal.valueOf(preview.t()).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                rows);
    }
}
