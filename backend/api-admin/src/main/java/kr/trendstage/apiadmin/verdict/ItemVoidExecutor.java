package kr.trendstage.apiadmin.verdict;

import kr.trendstage.apiadmin.approval.ActionType;
import kr.trendstage.apiadmin.approval.ApprovalExecutor;
import kr.trendstage.apiadmin.approval.ApprovalGate;
import kr.trendstage.judge.AdjustmentPolicy;
import kr.trendstage.judge.JudgeOutcome;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.TrendItemRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

/** ITEM_VOID 승인 실행 — 그 시점 입력으로 다시 계산해 한도 없이 반영하고 예상·실제 차액을 남긴다(K4). */
@Component
public class ItemVoidExecutor implements ApprovalExecutor {

    private final JudgeService judgeService;
    private final ApprovalGate gate;
    private final TrendItemRepository trendItems;
    private final Clock clock;

    public ItemVoidExecutor(JudgeService judgeService, ApprovalGate gate, TrendItemRepository trendItems, Clock clock) {
        this.judgeService = judgeService;
        this.gate = gate;
        this.trendItems = trendItems;
        this.clock = clock;
    }

    @Override public String actionType() { return ActionType.ITEM_VOID.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) judgeService.voidItem(request.getTargetRef(), p.get("reason"),
                clock.instant(), AdjustmentPolicy.approved(request.getId(), request.getApprover1()));
        return Map.of(
                "expectedAdjTotal", p.getOrDefault("expectedAdjTotal", ""),
                "adjTotal", a.adjTotal().toPlainString(),
                "newVerdictId", a.verdict().map(v -> v.getId().toString()).orElse(""));
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String name = trendItems.findById(request.getTargetRef()).map(i -> i.getCanonicalName()).orElse("(삭제된 항목)");
        return "판정 VOID · %s · 예상 차액 %s점 · 사유: %s".formatted(name, p.get("expectedAdjTotal"), p.get("reason"));
    }
}
