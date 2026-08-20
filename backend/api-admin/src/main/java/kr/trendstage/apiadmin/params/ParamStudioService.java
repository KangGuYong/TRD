package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.params.ParamSimulation;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.params.SimulationSummary;
import kr.trendstage.domain.params.VerdictSnapshot;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.entity.Verdict;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-600. 드래프트→시뮬레이션→2인 승인 요청까지만 다룬다(설계서 범위).
 * 시뮬레이션은 evidence_json에 동결된 신호만 읽는 읽기 전용 연산 — submissions 재조회 없음.
 */
@Service
public class ParamStudioService {

    private static final int SIM_WINDOW_DAYS = 180;
    private static final List<ParamStatus> ACTIVE_STATUSES = List.of(ParamStatus.DRAFT, ParamStatus.REVIEW);
    /** parameter_draft 동시 쓰기 직렬화용 고정 advisory lock 키. 임의의 상수. */
    private static final long PARAM_DRAFT_LOCK_KEY = 457_829_316L;

    private final ParameterDraftRepository drafts;
    private final ApprovalRequestRepository approvals;
    private final VerdictRepository verdicts;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EntityManager entityManager;
    private final CurrentParameterSetResolver currentParameterSetResolver;

    public ParamStudioService(ParameterDraftRepository drafts, ApprovalRequestRepository approvals,
                               VerdictRepository verdicts, AuditLogService auditLogService,
                               ObjectMapper objectMapper, Clock clock, EntityManager entityManager,
                               CurrentParameterSetResolver currentParameterSetResolver) {
        this.drafts = drafts;
        this.approvals = approvals;
        this.verdicts = verdicts;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.entityManager = entityManager;
        this.currentParameterSetResolver = currentParameterSetResolver;
    }

    @Transactional
    public ParameterDraft getOrCreateActiveDraft(UUID actorId) {
        // pg_advisory_xact_lock으로 check-then-create 연산을 직렬화: 두 관리자가 동시에 active draft 생성하는 경합 방지
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", PARAM_DRAFT_LOCK_KEY)
                .getSingleResult();

        return drafts.findFirstByStatusInOrderByCreatedAtDesc(ACTIVE_STATUSES)
                .orElseGet(() -> drafts.save(new ParameterDraft(actorId, defaultPayloadJson())));
    }

    @Transactional
    public ParameterDraft updateDraftValues(UUID actorId, AdminRole actorRole, int submitterTarget, double hitThreshold) {
        if (submitterTarget < 1) {
            throw new AdminValidationException("목표 제보자 수는 1 이상이어야 합니다");
        }
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("승인 대기 중인 드래프트는 수정할 수 없습니다");
        }
        draft.updatePayload(payloadJson(submitterTarget, hitThreshold));
        auditLogService.record(actorId, actorRole, "PARAM_DRAFT_UPDATE", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "submitterTarget", submitterTarget, "hitThreshold", hitThreshold));
        return draft;
    }

    @Transactional
    public ParameterDraft simulate(UUID actorId, AdminRole actorRole) {
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        ParameterSet draftParams = draft.toParameterSet(objectMapper);

        Instant since = clock.instant().minus(Duration.ofDays(SIM_WINDOW_DAYS));
        List<VerdictSnapshot> snapshots = verdicts.findCurrentNonVoidSince(since).stream()
                .map(this::toSnapshot)
                .toList();

        SimulationSummary summary = ParamSimulation.run(snapshots, draftParams);
        draft.recordSimResult(simResultJson(summary));

        auditLogService.record(actorId, actorRole, "PARAM_SIMULATE", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "changed", summary.changed(), "total", summary.total()));
        return draft;
    }

    @Transactional
    public ParameterDraft requestApproval(UUID actorId, AdminRole actorRole, String reason) {
        requireReason(reason);
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        if (draft.getSimResult() == null) {
            throw new AdminValidationException("시뮬레이션을 먼저 실행해야 승인 요청을 보낼 수 있습니다");
        }
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("이미 승인 대기 중인 드래프트입니다");
        }
        ApprovalRequest approval = approvals.save(new ApprovalRequest(
                "PARAM_APPLY", draft.getId(), actorId,
                "{\"reason\":\"%s\"}".formatted(reason == null ? "" : reason.replace("\"", "'"))));
        draft.moveToReview(approval.getId());

        auditLogService.record(actorId, actorRole, "PARAM_APPROVAL_REQUEST", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "reason", reason == null ? "" : reason, "approvalRequestId", approval.getId().toString()));
        return draft;
    }

    public ParameterSet currentOperationalParams() {
        return currentParameterSetResolver.resolve();
    }

    private VerdictSnapshot toSnapshot(Verdict v) {
        try {
            var node = objectMapper.readTree(v.getEvidenceJson());
            int distinctSubmitters = node.get("distinctSubmitters").asInt();
            int distinctPlatforms = node.get("distinctPlatforms").asInt();
            ReachLevel reach = v.getReachLevel();
            return new VerdictSnapshot(v.getResult(), reach, distinctSubmitters, distinctPlatforms);
        } catch (Exception e) {
            throw new IllegalStateException("evidence_json 파싱 실패: verdict=" + v.getId(), e);
        }
    }

    private String defaultPayloadJson() {
        ParameterSet d = ParameterSet.defaults();
        return payloadJson(d.submitterTarget, d.hitThreshold);
    }

    private String payloadJson(int submitterTarget, double hitThreshold) {
        return "{\"submitterTarget\":%d,\"hitThreshold\":%s}".formatted(submitterTarget, hitThreshold);
    }

    private String simResultJson(SimulationSummary s) {
        return "{\"changed\":%d,\"total\":%d,\"missToHit\":%d,\"hitToMiss\":%d,\"reachChanged\":%d}"
                .formatted(s.changed(), s.total(), s.missToHit(), s.hitToMiss(), s.reachChanged());
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new AdminValidationException("사유는 필수입니다");
        }
    }
}
