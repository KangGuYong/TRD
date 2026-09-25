package kr.trendstage.apiadmin.trend;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.domain.score.VerdictPlan;
import kr.trendstage.domain.signal.Platform;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.judge.JudgeService;
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
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-110/111. 목록은 전체 상태 조회, 상세의 예상 판정은 JudgeService.preview()가 판정과 같은 신호·계산으로
 * 만들고 저장하지 않는다(시딩 제외 — 판정과 같은 기준).
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
    private final JudgeService judgeService;
    private final Clock clock;

    public TrendItemAdminService(TrendItemRepository trendItems, SubmissionRepository submissions,
                                  SubmissionOrderRankRepository orderRanks, VerdictRepository verdicts,
                                  UserRepository users, UserGradeRepository userGrades,
                                  EndorsementRepository endorsements, JudgeService judgeService, Clock clock) {
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.orderRanks = orderRanks;
        this.verdicts = verdicts;
        this.users = users;
        this.userGrades = userGrades;
        this.endorsements = endorsements;
        this.judgeService = judgeService;
        this.clock = clock;
    }

    public record TrendItemSummary(String id, String canonicalName, String category, String state,
                                    String firstSeenAt, int submitterCount, String currentResult) {}

    public record SubmissionRow(String submissionId, String userHandle, Integer orderRank,
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
                    // 상세(ADM-111)·판정과 같은 기준: 서로 다른 제보자, 시딩 제외
                    long submitterCount = submissions.countDistinctSubmitters(item.getId(), SubmissionResult.VOID);
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
        JudgeService.Preview preview = judgeService.preview(trendItemId);
        int distinctSubmitters = preview.signal().distinctSubmitters();   // 시딩 제외 — 판정과 같은 기준
        int distinctPlatforms = preview.signal().distinctPlatforms();
        Verdict current = verdicts.findCurrentByTrendItemId(trendItemId).orElse(null);
        VerdictPlan plan = current == null ? preview.plan() : null;

        List<SubmissionRow> rows = subs.stream()
                .sorted(Comparator.comparingInt(s -> rankById.getOrDefault(s.getId(), Integer.MAX_VALUE)))
                .map(s -> new SubmissionRow(
                        s.getId().toString(),
                        users.findById(s.getUserId()).map(UserAccount::getHandle).orElse("-"),
                        rankById.get(s.getId()), s.getConfidence(),
                        userGrades.findTopByUserIdOrderByComputedAtDesc(s.getUserId())
                                .map(g -> g.getTrustIndex().doubleValue()).orElse(null),
                        DISPLAY_FORMAT.format(s.getCreatedAt()),
                        Platform.labelOf(s.getPlatform()), s.getOneLine(), s.getEvidenceUrl()))
                .toList();

        Instant deadline = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());

        return new TrendItemDetail(
                item.getId().toString(), item.getCanonicalName(), item.getCategory().name(), item.getState().name(),
                DISPLAY_FORMAT.format(item.getFirstSeenAt()), DISPLAY_FORMAT.format(deadline),
                Duration.between(now, deadline).toDays(), item.getJudgmentDeadlineOverride() != null,
                distinctSubmitters, distinctPlatforms, endorsements.countByTrendItemId(trendItemId),
                current == null ? null : current.getResult().name(),
                current == null || current.getReachLevel() == null ? null : current.getReachLevel().name(),
                current == null || current.getScoreT() == null ? null : current.getScoreT().toPlainString(),
                current == null ? null : DISPLAY_FORMAT.format(current.getJudgedAt()),
                plan == null ? null : plan.result().name(),
                plan == null || plan.reach() == null ? null : plan.reach().name(),
                plan == null ? null : BigDecimal.valueOf(plan.t()).setScale(4, RoundingMode.HALF_UP).toPlainString(),
                rows);
    }
}
