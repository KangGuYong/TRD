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

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;

/** VERDICT_REJUDGE 승인 실행 — 그 시점 입력으로 다시 계산해 한도 없이 반영하고 예상·실제 차액을 남긴다(K4). */
@Component
public class VerdictRejudgeExecutor implements ApprovalExecutor {

    private final JudgeService judgeService;
    private final ApprovalGate gate;
    private final TrendItemRepository trendItems;
    private final Clock clock;

    public VerdictRejudgeExecutor(JudgeService judgeService, ApprovalGate gate, TrendItemRepository trendItems, Clock clock) {
        this.judgeService = judgeService;
        this.gate = gate;
        this.trendItems = trendItems;
        this.clock = clock;
    }

    @Override public String actionType() { return ActionType.VERDICT_REJUDGE.name(); }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        JudgeOutcome.Applied a = (JudgeOutcome.Applied) judgeService.rejudge(request.getTargetRef(), p.get("reason"),
                clock.instant(), AdjustmentPolicy.approved(request.getId(), request.getApprover1()));
        return Map.of(
                "expectedAdjTotal", p.getOrDefault("expectedAdjTotal", ""),
                "adjTotal", a.adjTotal().toPlainString(),
                "newVerdictId", a.verdict().map(v -> v.getId().toString()).orElse(""),
                "newResult", a.verdict().map(v -> v.getResult().name()).orElse(""));
    }

    @Override
    public String describe(ApprovalRequest request) {
        Map<String, String> p = gate.payload(request);
        String name = trendItems.findById(request.getTargetRef()).map(i -> i.getCanonicalName()).orElse("(삭제된 항목)");
        return "재판정 · %s · 예상 차액 %s점 · 사유: %s".formatted(name, points(p.get("expectedAdjTotal")), p.get("reason"));
    }

    /** 요약용 점수 표기 — "182.0000" → "182", "-12.5000" → "-12.5". 비었거나 숫자가 아니면 그대로(비었으면 "-"). */
    static String points(String raw) {
        if (raw == null || raw.isBlank()) return "-";
        try {
            return new BigDecimal(raw.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return raw;
        }
    }
}
