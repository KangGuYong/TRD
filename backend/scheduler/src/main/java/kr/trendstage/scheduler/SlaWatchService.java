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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * sla_watch의 처리 단위(SP3 §7). 후보 id 조회와 단위별 트랜잭션을 나눈다 — 한 행이 실패해도 나머지는 커밋되고,
 * 잠금은 단위 하나 동안만 쥔다. SlaWatchJob(다른 빈)이 부른다(self-invocation 금지).
 * 각 단위는 잠근 뒤 조건을 다시 확인한다 — 후보 조회와 처리 사이에 사람이 먼저 처리했을 수 있다.
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

    // ── 신고 4h ──────────────────────────────────────────────

    /** 4h를 넘긴 OPEN 신고 중 아직 자동 처리하지 않은 것. */
    @Transactional(readOnly = true)
    public List<UUID> overdueReportIds(Instant now) {
        return reports.findByStatusAndAutoHiddenAtIsNullAndCreatedAtLessThanEqual(ReportStatus.OPEN, now.minus(REPORT_SLA))
                .stream().map(Report::getId).toList();
    }

    /**
     * 공개 중인 항목을 임시 비공개. 신고는 OPEN 그대로 — 결정은 사람(K9).
     * 잠금 순서는 사람의 결정과 같다(신고 → 항목). 잠근 뒤 OPEN·미처리를 다시 확인한다.
     * @return 처리했으면 true
     */
    @Transactional
    public boolean autoHideReport(UUID reportId, Instant now) {
        Report r = reports.findByIdForUpdate(reportId).orElse(null);
        if (r == null || r.getStatus() != ReportStatus.OPEN || r.getAutoHiddenAt() != null
                || r.getCreatedAt().isAfter(now.minus(REPORT_SLA))) {
            return false;
        }
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
        return true;
    }

    // ── 병합 24h ─────────────────────────────────────────────

    /** 24h를 넘긴 처리 대기(PENDING) 병합 후보. */
    @Transactional(readOnly = true)
    public List<UUID> stalledMergeIds(Instant now) {
        return mergeQueue.findByStatusAndCreatedAtLessThanEqual(MergeQueueStatus.PENDING, now.minus(MERGE_SLA))
                .stream().map(MergeQueueEntry::getId).toList();
    }

    /**
     * 두 항목의 관측 마감을 now + 24h까지(상한 최초 제보 + 21일). 판정된 항목은 건드리지 않는다(K10).
     * 잠금 순서는 병합 결정과 같다(큐 행 → 항목 id 순, MergeService.lockPair). 잠근 뒤 큐 행이 아직 PENDING인지 다시 확인한다.
     * @return 한 항목이라도 연장했으면 true
     */
    @Transactional
    public boolean extendStalledMerge(UUID queueId, Instant now) {
        MergeQueueEntry e = mergeQueue.findByIdForUpdate(queueId).orElse(null);
        if (e == null || e.getStatus() != MergeQueueStatus.PENDING || e.getCreatedAt().isAfter(now.minus(MERGE_SLA))) {
            return false;
        }
        boolean acted = false;
        Map<UUID, TrendItem> pair = mergeService.lockPair(e.getNewTrendItemId(), e.getOldTrendItemId());
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
            acted = true;
        }
        return acted;
    }

    // ── 90일 미접속 관리자 ───────────────────────────────────

    /** 활성 계정 중 마지막 활동(로그인·활성화·재활성화·생성 중 가장 늦은 시각)이 90일을 넘긴 것. */
    @Transactional(readOnly = true)
    public List<UUID> inactiveAdminIds(Instant now) {
        return accounts.findInactiveIdsSince(now.minus(ADMIN_INACTIVE));
    }

    /**
     * 계정을 잠그고 미접속을 다시 확인한 뒤 비활성화. 마지막 활성 ADMIN은 건너뛴다(K11).
     * @return 비활성화했으면 true
     */
    @Transactional
    public boolean disableIfInactive(UUID accountId, Instant now) {
        AdminAccount a = accounts.findByIdForUpdate(accountId).orElse(null);
        if (a == null || !a.isActive() || a.lastActiveAt().isAfter(now.minus(ADMIN_INACTIVE))) {
            return false;
        }
        if (a.getRole() == AdminRole.ADMIN
                && accounts.countByRoleAndDisabledAtIsNullAndActivatedAtIsNotNull(AdminRole.ADMIN) <= 1) {
            log.warn("sla_watch: 마지막 활성 ADMIN {}은 90일 미접속이지만 비활성화하지 않습니다", a.getLoginId());
            return false;
        }
        a.disable(now);
        auditLogService.record(null, null, "SLA_ADMIN_DISABLE", "ADMIN_ACCOUNT", a.getId(), Map.of(
                "loginId", a.getLoginId(),
                "lastLoginAt", a.getLastLoginAt() == null ? "" : a.getLastLoginAt().toString()));
        return true;
    }
}
