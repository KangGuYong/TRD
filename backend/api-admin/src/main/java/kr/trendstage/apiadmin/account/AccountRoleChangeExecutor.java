package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalConflictException;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** ACCOUNT_ROLE_CHANGE 승인 — 요청 시점 역할(from)이 그대로일 때만 바꾼다. 다르면 409, 요청은 PENDING으로 남는다. */
@Component
public class AccountRoleChangeExecutor implements ApprovalExecutor {

    private final AdminAccountRepository accounts;
    private final ApprovalGate gate;
    private final Clock clock;

    public AccountRoleChangeExecutor(AdminAccountRepository accounts, ApprovalGate gate, Clock clock) {
        this.accounts = accounts; this.gate = gate; this.clock = clock;
    }

    @Override public String actionType() { return ActionType.ACCOUNT_ROLE_CHANGE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        AdminAccount account = accounts.findByIdForUpdate(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 계정 없음: " + request.getTargetRef()));
        AdminRole from = AdminRole.valueOf(p.get("from"));
        if (account.getRole() != from) {
            throw new ApprovalConflictException("요청 이후 역할이 이미 %s로 바뀌었습니다 — 반려하세요".formatted(account.getRole()));
        }
        account.changeRole(AdminRole.valueOf(p.get("to")), clock.instant());
        return Map.of("from", p.get("from"), "to", p.get("to"));
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String loginId = accounts.findById(request.getTargetRef()).map(AdminAccount::getLoginId).orElse("(삭제된 계정)");
        return "역할 변경 · %s · %s → %s · 사유: %s".formatted(loginId, p.get("from"), p.get("to"), p.get("reason"));
    }
}
