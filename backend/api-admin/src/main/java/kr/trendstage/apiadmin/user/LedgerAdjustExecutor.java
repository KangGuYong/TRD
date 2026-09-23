package kr.trendstage.apiadmin.user;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ScoreLedgerEntry;
import kr.trendstage.persistence.repo.UserRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/** LEDGER_ADJ 승인 실행 — 요청 금액 그대로 ADJ 한 행(승인 정보 포함). */
@Component
public class LedgerAdjustExecutor implements ApprovalExecutor {

    private final LedgerAdjustService service;
    private final ApprovalGate gate;
    private final UserRepository users;

    public LedgerAdjustExecutor(LedgerAdjustService service, ApprovalGate gate, UserRepository users) {
        this.service = service; this.gate = gate; this.users = users;
    }

    @Override public String actionType() { return ActionType.LEDGER_ADJ.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        ScoreLedgerEntry saved = service.record(request.getTargetRef(), new BigDecimal(p.get("amount")), p.get("reason"),
                request.getId(), request.getApprover1());
        return Map.of("amount", p.get("amount"), "ledgerId", saved.getId().toString());
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String handle = users.findById(request.getTargetRef()).map(u -> u.getHandle()).orElse("(삭제된 유저)");
        return "원장 조정 · %s · %s점 · 사유: %s".formatted(handle, p.get("amount"), p.get("reason"));
    }
}
