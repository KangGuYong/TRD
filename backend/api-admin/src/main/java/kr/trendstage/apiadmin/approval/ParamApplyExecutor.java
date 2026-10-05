package kr.trendstage.apiadmin.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;

@Component
public class ParamApplyExecutor implements ApprovalExecutor {

    private final ParameterDraftRepository drafts;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public ParamApplyExecutor(ParameterDraftRepository drafts, Clock clock, ObjectMapper objectMapper) {
        this.drafts = drafts;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    public String actionType() {
        return "PARAM_APPLY";
    }

    @Override
    public Map<String, String> execute(ApprovalRequest request) {
        ParameterDraft draft = drafts.findById(request.getTargetRef())
                .orElseThrow(() -> new IllegalStateException("대상 드래프트 없음: " + request.getTargetRef()));
        if (draft.getStatus() != ParamStatus.REVIEW) {
            throw new AdminValidationException("드래프트가 이미 처리된 상태입니다: " + draft.getStatus());
        }
        draft.markApplied(clock.instant());
        return Map.of("draftId", draft.getId().toString());
    }

    @Override
    public void onReject(ApprovalRequest request) {
        drafts.findById(request.getTargetRef()).ifPresent(ParameterDraft::returnToDraft);
    }

    @Override
    public String describe(ApprovalRequest request) {
        String base = "파라미터 적용 · 드래프트 " + request.getTargetRef().toString().substring(0, 8);
        return drafts.findById(request.getTargetRef())
                .map(ParameterDraft::getBacktestResult)
                .map(json -> base + " · " + backtestSummary(json))
                .orElse(base);
    }

    /** 승인자가 볼 근거(SP4 §5.3): "데이터셋 '이름'(N건) — 정밀도 a→b, 재현율 c→d, 판정 변경 k건". */
    private String backtestSummary(String json) {
        try {
            JsonNode r = objectMapper.readTree(json);
            JsonNode report = r.path("report");
            return "데이터셋 '%s'(%d건) — 정밀도 %s→%s, 재현율 %s→%s, 판정 변경 %d건".formatted(
                    r.path("datasetName").asText(), r.path("caseCount").asInt(),
                    ratio(report.path("current").path("precision")), ratio(report.path("draft").path("precision")),
                    ratio(report.path("current").path("recall")), ratio(report.path("draft").path("recall")),
                    report.path("changedCount").asInt());
        } catch (Exception e) {
            return "백테스트 결과를 읽지 못했습니다";
        }
    }

    private static String ratio(JsonNode n) {
        return n.isNumber() ? String.format(Locale.ROOT, "%.2f", n.asDouble()) : "—";
    }
}
