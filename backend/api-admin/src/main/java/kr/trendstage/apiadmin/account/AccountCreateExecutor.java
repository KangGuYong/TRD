package kr.trendstage.apiadmin.account;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** ACCOUNT_CREATE 승인 — 계정을 활성화한다. 반려되면 미활성으로 남는다(아이디 점유). */
@Component
public class AccountCreateExecutor implements ApprovalExecutor {

    private final AdminAccountRepository accounts;
    private final ApprovalGate gate;
    private final Clock clock;

    public AccountCreateExecutor(AdminAccountRepository accounts, ApprovalGate gate, Clock clock) {
        this.accounts = accounts; this.gate = gate; this.clock = clock;
    }

    @Override public String actionType() { return ActionType.ACCOUNT_CREATE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        AdminAccount account = accounts.findById(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 계정 없음: " + request.getTargetRef()));
        account.activate(clock.instant());
        return Map.of("loginId", account.getLoginId(), "role", account.getRole().name());
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        return "계정 생성 · %s(%s) · %s".formatted(p.get("loginId"), p.get("displayName"), p.get("role"));
    }
}
