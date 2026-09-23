package kr.trendstage.apiadmin.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.approval.ApprovalService;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** ADM-620 승인 대기함. 2인 승인 = 요청자 + 승인자 1명(SP3 K1). 확정은 ADMIN, 조회는 ADMIN/AUDITOR. */
@RestController
@RequestMapping("/admin/approvals")
public class ApprovalController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final ApprovalService service;
    private final AdminAccountRepository accounts;
    private final ObjectMapper objectMapper;

    public ApprovalController(ApprovalService service, AdminAccountRepository accounts, ObjectMapper objectMapper) {
        this.service = service;
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    public record ApprovalRequestResponse(String id, String actionType, String targetRef,
                                          String requestedBy, String requestedByName, int approvals, int requiredApprovals,
                                          String status, String reason, String summary, String createdAt, String resolvedAt) {}
    public record RejectRequest(String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    public List<ApprovalRequestResponse> list() {
        return service.listPending().stream().map(this::toResponse).toList();
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasRole('ADMIN')")
    public ApprovalRequestResponse approve(@PathVariable UUID id, @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.approve(actor.id(), actor.role(), id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasRole('ADMIN')")
    public ApprovalRequestResponse reject(@PathVariable UUID id, @RequestBody RejectRequest req,
                                           @AuthenticationPrincipal AdminPrincipal actor) {
        return toResponse(service.reject(actor.id(), actor.role(), id, req.reason()));
    }

    private ApprovalRequestResponse toResponse(ApprovalRequest req) {
        String requestedByName = accounts.findById(req.getRequestedBy())
                .map(AdminAccount::getDisplayName)
                .orElse(req.getRequestedBy().toString());
        int approvalCount = req.getApprover1() != null ? 1 : 0;
        return new ApprovalRequestResponse(
                req.getId().toString(), req.getActionType(), req.getTargetRef().toString(),
                req.getRequestedBy().toString(), requestedByName, approvalCount, 1, req.getStatus().name(),
                extractReason(req.getPayload()), service.describe(req),
                DISPLAY_FORMAT.format(req.getCreatedAt()),
                req.getResolvedAt() == null ? null : DISPLAY_FORMAT.format(req.getResolvedAt()));
    }

    private String extractReason(String payloadJson) {
        try {
            var node = objectMapper.readTree(payloadJson);
            return node.has("reason") ? node.get("reason").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
