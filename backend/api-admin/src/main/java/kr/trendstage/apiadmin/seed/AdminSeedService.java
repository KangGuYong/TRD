package kr.trendstage.apiadmin.seed;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.score.TrustIndex;
import kr.trendstage.domain.trend.NameNormalizer;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.entity.UserAccount;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ADM-500 시딩 등록·담당자별 적중률. 제보 생성 로직은 SubmissionService.create()(api-public)와
 * 동일 알고리즘이지만, api-admin → api-public 모듈 의존을 피하기 위해 의도적으로 소규모 중복한다
 * (VerdictAdminService가 VerdictRunner 판정 로직을 같은 이유로 중복하는 것과 동일 전례).
 */
@Service
public class AdminSeedService {

    private static final Set<Integer> VALID_CONFIDENCE = Set.of(10, 30, 50);

    private final AdminAccountRepository adminAccounts;
    private final UserRepository users;
    private final TrendItemRepository trendItems;
    private final SubmissionRepository submissions;
    private final AuditLogService auditLogService;
    private final CurrentParameterSetResolver currentParams;

    public AdminSeedService(AdminAccountRepository adminAccounts, UserRepository users,
                            TrendItemRepository trendItems, SubmissionRepository submissions,
                            AuditLogService auditLogService, CurrentParameterSetResolver currentParams) {
        this.adminAccounts = adminAccounts;
        this.users = users;
        this.trendItems = trendItems;
        this.submissions = submissions;
        this.auditLogService = auditLogService;
        this.currentParams = currentParams;
    }

    public record SeedSubmissionRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank String category,
            @NotBlank @Size(max = 60) String platform,
            @NotBlank String evidenceUrl,
            @NotNull Integer confidence,
            @NotBlank @Size(max = 200) String oneLine) {}
    public record SeedSubmissionResult(String submissionId, String canonicalName, String trendItemId) {}
    public record SeedAccuracyRow(String operatorName, int hit, int miss, int judged, double trustIndex) {}

    @Transactional
    public SeedSubmissionResult registerSeed(UUID actorId, AdminRole actorRole, SeedSubmissionRequest req) {
        if (!VALID_CONFIDENCE.contains(req.confidence())) {
            throw new AdminValidationException("confidence는 10/30/50 중 하나여야 합니다");
        }

        TrendCategory category;
        try {
            category = TrendCategory.valueOf(req.category());
        } catch (IllegalArgumentException e) {
            throw new AdminValidationException("유효하지 않은 카테고리입니다");
        }

        AdminAccount actor = adminAccounts.findById(actorId).orElseThrow();
        UUID seedUserId = actor.getSeedUserId();
        if (seedUserId == null) {
            UserAccount seedUser = users.save(new UserAccount("seed_" + actor.getLoginId()));
            actor.linkSeedUser(seedUser.getId());
            seedUserId = seedUser.getId();
        }

        String normalized = NameNormalizer.normalize(req.name());
        Instant now = Instant.now();

        TrendItem item = trendItems.findByNormalizedKey(normalized).orElse(null);
        if (item != null) {
            boolean dup = submissions.existsByTrendItemIdAndUserIdAndResultNot(
                    item.getId(), seedUserId, SubmissionResult.VOID);
            if (dup) throw new AdminValidationException("이미 이 계정으로 시딩한 항목입니다");
        } else {
            item = trendItems.save(new TrendItem(req.name(), normalized, category, now));
            item.transitionTo(TrendState.PENDING);
        }

        Submission sub = submissions.save(new Submission(
                seedUserId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                false, true));

        auditLogService.record(actorId, actorRole, "SEED_SUBMISSION_CREATE", "TREND_ITEM", item.getId(),
                Map.of("name", req.name(), "confidence", req.confidence()));

        return new SeedSubmissionResult(sub.getId().toString(), item.getCanonicalName(), item.getId().toString());
    }

    @Transactional(readOnly = true)
    public List<SeedAccuracyRow> listAccuracy() {
        ParameterSet p = currentParams.resolve();
        return adminAccounts.findAll().stream()
                .filter(a -> a.getSeedUserId() != null)
                .map(a -> {
                    long hit = submissions.countByUserIdAndResult(a.getSeedUserId(), SubmissionResult.HIT);
                    long miss = submissions.countByUserIdAndResult(a.getSeedUserId(), SubmissionResult.MISS);
                    double ti = TrustIndex.compute((int) hit, (int) miss, p);
                    return new SeedAccuracyRow(a.getDisplayName(), (int) hit, (int) miss, (int) (hit + miss), ti);
                })
                .toList();
    }
}
