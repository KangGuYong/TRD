package kr.trendstage.apiadmin.user;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.web.AdminNotFoundException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.repo.ScoreLedgerRepository;
import kr.trendstage.persistence.repo.UserRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ADM-311 수동 상쇄 원장(ADJ). ADMIN이 100점 이하면 즉시 기록, OPERATOR(상신, 02 §1.1)이거나 100점 초과면 승인 요청(K3).
 * 행은 판정·제보와 연결되지 않는다. 반감기는 현재 파라미터, 감쇠 기준은 기록 시각.
 */
@Service
public class LedgerAdjustService {

    static final BigDecimal MAX_ABS = new BigDecimal("9999");
    static final String REASON_PREFIX = "수동 조정 · ";

    private final UserRepository users;
    private final ScoreLedgerRepository ledger;
    private final ApprovalGate gate;
    private final CurrentParameterSetResolver params;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public LedgerAdjustService(UserRepository users, ScoreLedgerRepository ledger, ApprovalGate gate,
                               CurrentParameterSetResolver params, AuditLogService auditLogService, Clock clock) {
        this.users = users; this.ledger = ledger; this.gate = gate; this.params = params;
        this.auditLogService = auditLogService; this.clock = clock;
    }

    public record AdjustResult(String status, String ledgerId, String approvalRequestId, BigDecimal amount) {
        public boolean pending() { return "PENDING_APPROVAL".equals(status); }
    }

    @Transactional
    public AdjustResult adjust(UUID userId, BigDecimal amount, String reason, UUID actorId, AdminRole actorRole) {
        if (reason == null || reason.isBlank()) throw new AdminValidationException("사유는 필수입니다");
        if (amount == null || amount.signum() == 0) throw new AdminValidationException("금액은 0이 아니어야 합니다");
        BigDecimal amt = amount.setScale(4, RoundingMode.HALF_UP);
        if (amt.abs().compareTo(MAX_ABS) > 0) throw new AdminValidationException("금액은 ±9999 이내여야 합니다");
        if (!users.existsById(userId)) throw new AdminNotFoundException("존재하지 않는 유저입니다");

        if (actorRole == AdminRole.ADMIN && !gate.exceeds(amt)) {
            ScoreLedgerEntry saved = record(userId, amt, reason, null, null);
            auditLogService.record(actorId, actorRole, "LEDGER_ADJ", "USER", userId, Map.of(
                    "amount", amt.toPlainString(), "reason", reason, "ledgerId", saved.getId().toString()));
            return new AdjustResult("APPLIED", saved.getId().toString(), null, amt);
        }
        ApprovalRequest r = gate.request(ActionType.LEDGER_ADJ, userId,
                Map.of("amount", amt.toPlainString(), "reason", reason), actorId, actorRole);
        return new AdjustResult("PENDING_APPROVAL", null, r.getId().toString(), amt);
    }

    /** 실행기와 공유 — 승인 실행이면 승인 정보를 채운다. */
    ScoreLedgerEntry record(UUID userId, BigDecimal amount, String reason, UUID approvalId, UUID approvedBy) {
        Instant now = clock.instant();
        return ledger.save(ScoreLedgerEntry.adjustment(userId, amount, REASON_PREFIX + reason, approvalId, approvedBy,
                params.resolve().halflifeDays, now));
    }
}
