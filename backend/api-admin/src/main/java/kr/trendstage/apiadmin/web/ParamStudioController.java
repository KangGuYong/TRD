package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.params.ParamStudioService;
import kr.trendstage.domain.params.DraftValues;
import kr.trendstage.domain.signal.IndependenceMode;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** ADM-600. 드래프트 편집·시뮬레이션·승인요청은 OPERATOR 이상(02 §1.1 paramDraft와 동일 권한). */
@RestController
@RequestMapping("/admin/params")
public class ParamStudioController {

    private final ParamStudioService service;
    private final ObjectMapper objectMapper;

    public ParamStudioController(ParamStudioService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    /** 9개 모두 필수 — 빠지면 "필드명: 값이 필요합니다"(422). */
    public record UpdateDraftRequest(Integer targetFloor, Double targetRatio, Integer activeWindowDays, Double hitThreshold,
                                     Double persistenceFloor, Integer persistenceFullDays, Double diversityFloor,
                                     Integer diversityFullPlatforms, String independenceMode) {
        DraftValues toValues() {
            IndependenceMode mode = mode(independenceMode);
            try {
                return new DraftValues(req(targetFloor, "targetFloor"), req(targetRatio, "targetRatio"),
                        req(activeWindowDays, "activeWindowDays"), req(hitThreshold, "hitThreshold"),
                        req(persistenceFloor, "persistenceFloor"), req(persistenceFullDays, "persistenceFullDays"),
                        req(diversityFloor, "diversityFloor"), req(diversityFullPlatforms, "diversityFullPlatforms"), mode);
            } catch (IllegalArgumentException e) {
                throw new AdminValidationException(e.getMessage());
            }
        }

        private static <T> T req(T value, String name) {
            if (value == null) throw new AdminValidationException(name + ": 값이 필요합니다");
            return value;
        }

        private static IndependenceMode mode(String value) {
            if (value == null) throw new AdminValidationException("independenceMode: 값이 필요합니다");
            try {
                return IndependenceMode.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new AdminValidationException("independenceMode: OFF · DEVICE · DEVICE_OR_IP 중 하나여야 합니다");
            }
        }
    }

    public record ApprovalRequestBody(String reason) {}
    public record SimulationSummaryResponse(int changed, int total, int missToHit, int hitToMiss, int reachChanged) {}
    public record ParameterDraftResponse(String draftId, String status, DraftValues values, DraftValues current,
                                          SimulationSummaryResponse simResult, JsonNode backtestResult) {}

    @GetMapping("/draft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public ParameterDraftResponse getDraft(@AuthenticationPrincipal AdminPrincipal actor) {
        if (actor.role() == AdminRole.AUDITOR) {
            // 조회는 쓰지 않는다 — 활성 드래프트가 없으면 운영값만 보여 준다(status NONE)
            return service.findActiveDraft().map(this::toResponse).orElseGet(this::operationalView);
        }
        return toResponse(service.getOrCreateActiveDraft(actor.id()));
    }

    private ParameterDraftResponse operationalView() {
        DraftValues current = DraftValues.of(service.currentOperationalParams());
        return new ParameterDraftResponse(null, "NONE", current, current, null, null);
    }

    @PutMapping("/draft")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse updateDraft(@RequestBody UpdateDraftRequest req,
                                               @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.updateDraftValues(actor.id(), actor.role(), req.toValues()));
    }

    @PostMapping("/draft/simulate")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse simulate(@AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.simulate(actor.id(), actor.role()));
    }

    @PostMapping("/draft/request-approval")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse requestApproval(@RequestBody(required = false) ApprovalRequestBody req,
                                                   @AuthenticationPrincipal AdminPrincipal actor) {
        String reason = req == null ? null : req.reason();
        return toResponse(service.requestApproval(actor.id(), actor.role(), reason));
    }

    private ParameterDraftResponse toResponse(ParameterDraft draft) {
        try {
            SimulationSummaryResponse sim = null;
            if (draft.getSimResult() != null) {
                var s = objectMapper.readTree(draft.getSimResult());
                sim = new SimulationSummaryResponse(
                        s.get("changed").asInt(), s.get("total").asInt(),
                        s.get("missToHit").asInt(), s.get("hitToMiss").asInt(), s.get("reachChanged").asInt());
            }
            JsonNode backtest = draft.getBacktestResult() == null ? null : objectMapper.readTree(draft.getBacktestResult());
            return new ParameterDraftResponse(draft.getId().toString(), draft.getStatus().name(),
                    draft.toDraftValues(objectMapper), DraftValues.of(service.currentOperationalParams()), sim, backtest);
        } catch (Exception e) {
            throw new IllegalStateException("드래프트 응답 변환 실패: " + draft.getId(), e);
        }
    }
}
