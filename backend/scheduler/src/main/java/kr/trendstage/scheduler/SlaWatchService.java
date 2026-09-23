package kr.trendstage.scheduler;

import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.verdict.DeadlineWindow;
import kr.trendstage.merge.MergeService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.MergeQueueEntry;
import kr.trendstage.persistence.entity.Report;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.MergeQueueRepository;
import kr.trendstage.persistence.repo.ReportRepository;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.MergeQueueStatus;
import kr.trendstage.persistence.type.ReportStatus;
import kr.trendstage.persistence.type.TrendState;
import kr.trendstage.persistence.type.TrendVisibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * sla_watch의 처리 단계(SP3 §7). 각 메서드가 한 트랜잭션 — SlaWatchJob(다른 빈)이 부른다(self-invocation 금지).
 * 감사 로그 액터는 NULL(시스템). 자동 조치는 가역적인 것만(R4): 임시 비공개, 마감 연장, 계정 비활성화.
 */
@Service
public class SlaWatchService {

    private static final Logger log = LoggerFactory.getLogger(SlaWatchService.class);

    static final Duration REPORT_SLA = Duration.ofHours(4);
    static final Duration MERGE_SLA = Duration.ofHours(24);
    static final Duration ADMIN_INACTIVE = Duration.ofDays(90);
    private static final Set<TrendState> OBSERVING = EnumSet.of(TrendState.DRAFT, TrendState.PENDING, TrendState.JUDGING);

    private final ReportRepository reports;
    private final TrendItemRepository trendItems;
    private final MergeQueueRepository mergeQueue;
    private final MergeService mergeService;
    private final VerdictRepository verdicts;
    private final AdminAccountRepository accounts;
    private final AuditLogService auditLogService;

    public SlaWatchService(ReportRepository reports, TrendItemRepository trendItems, MergeQueueRepository mergeQueue,
                           MergeService mergeService, VerdictRepository verdicts, AdminAccountRepository accounts,
                           AuditLogService auditLogService) {
        this.reports = reports; this.trendItems = trendItems; this.mergeQueue = mergeQueue;
        this.mergeService = mergeService; this.verdicts = verdicts; this.accounts = accounts;
        this.auditLogService = auditLogService;
    }

    /** 신고 4h → 공개 중인 항목을 임시 비공개. 신고는 OPEN 그대로 — 결정은 사람(K9). */
    @Transactional
    public int autoHideOverdueReports(Instant now) {
        int n = 0;
        for (Report r : reports.findByStatusAndAutoHiddenAtIsNullAndCreatedAtLessThanEqual(ReportStatus.OPEN, now.minus(REPORT_SLA))) {
            TrendItem item = trendItems.findByIdForUpdate(r.getTrendItemId())
                    .orElseThrow(() -> new IllegalStateException("신고 대상 항목 없음: " + r.getTrendItemId()));
            TrendVisibility before = item.getVisibility();
            if (before == TrendVisibility.PUBLIC) {
                item.applyVisibility(TrendVisibility.TEMP_HIDDEN);
            }
            r.markAutoHidden(now);
            auditLogService.record(null, null, "SLA_AUTO_HIDE", "REPORT", r.getId(), Map.of(
                    "trendItemId", item.getId().toString(),
                    "visibilityBefore", before.name(),
                    "ageHours", String.valueOf(Duration.between(r.getCreatedAt(), now).toHours())));
            n++;
        }
        return n;
    }

    /** 병합 24h → 두 항목의 관측 마감을 now + 24h까지(상한 최초 제보 + 21일). 판정된 항목은 건드리지 않는다(K10). */
    @Transactional
    public int extendStalledMerges(Instant now) {
        int n = 0;
        for (MergeQueueEntry e : mergeQueue.findByStatusAndCreatedAtLessThanEqual(MergeQueueStatus.PENDING, now.minus(MERGE_SLA))) {
            Map<java.util.UUID, TrendItem> pair = mergeService.lockPair(e.getNewTrendItemId(), e.getOldTrendItemId());
            for (TrendItem item : pair.values()) {
                if (!OBSERVING.contains(item.getState()) || verdicts.existsByTrendItemIdAndSupersedesIsNull(item.getId())) {
                    continue;
                }
                Instant before = DeadlineWindow.effectiveDeadline(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride());
                Optional<Instant> target = DeadlineWindow.slaExtended(item.getFirstSeenAt(), item.getJudgmentDeadlineOverride(), now);
                if (target.isEmpty()) continue;
                item.extendJudgmentDeadline(target.get());
                if (item.getState() == TrendState.JUDGING && target.get().isAfter(now)) {
                    item.transitionTo(TrendState.PENDING);
                }
                auditLogService.record(null, null, "SLA_GRACE_EXTEND", "TREND_ITEM", item.getId(), Map.of(
                        "queueId", e.getId().toString(),
                        "deadlineBefore", before.toString(),
                        "deadlineAfter", target.get().toString()));
                n++;
            }
        }
        return n;
    }

    /** 90일 미접속 관리자 비활성화. 마지막 활성 ADMIN은 건너뛴다(K11). */
    @Transactional
    public int disableInactiveAdmins(Instant now) {
        int n = 0;
        for (AdminAccount a : accounts.findInactiveSince(now.minus(ADMIN_INACTIVE))) {
            if (a.getRole() == AdminRole.ADMIN
                    && accounts.countByRoleAndDisabledAtIsNullAndActivatedAtIsNotNull(AdminRole.ADMIN) <= 1) {
                log.warn("sla_watch: 마지막 활성 ADMIN {}은 90일 미접속이지만 비활성화하지 않습니다", a.getLoginId());
                continue;
            }
            a.disable(now);
            accounts.flush();   // 다음 반복의 활성 ADMIN 수 조회에 반영
            auditLogService.record(null, null, "SLA_ADMIN_DISABLE", "ADMIN_ACCOUNT", a.getId(), Map.of(
                    "loginId", a.getLoginId(),
                    "lastLoginAt", a.getLastLoginAt() == null ? "" : a.getLastLoginAt().toString()));
            n++;
        }
        return n;
    }
}
