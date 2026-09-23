package kr.trendstage.apiadmin.approval;

import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.repo.ParameterDraftRepository;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;

@Component
public class ParamApplyExecutor implements ApprovalExecutor {

    private final ParameterDraftRepository drafts;
    private final Clock clock;

    public ParamApplyExecutor(ParameterDraftRepository drafts, Clock clock) {
        this.drafts = drafts;
        this.clock = clock;
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
        return "파라미터 적용 · 드래프트 " + request.getTargetRef().toString().substring(0, 8);
    }
}
