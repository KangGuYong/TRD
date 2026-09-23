package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.user.AdminUserService;
import kr.trendstage.apiadmin.user.LedgerAdjustService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** ADM-311 유저 원장. 조회 R/O/A/Au(근거 화면), 수동 ADJ는 O/A(OPERATOR는 상신 → 승인). */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

    private final AdminUserService users;
    private final LedgerAdjustService adjustments;

    public AdminUserController(AdminUserService users, LedgerAdjustService adjustments) {
        this.users = users;
        this.adjustments = adjustments;
    }

    public record AdjustRequest(BigDecimal amount, String reason) {}

    @GetMapping
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<AdminUserService.UserHit> search(@RequestParam(required = false) String handle) {
        return users.search(handle);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR')")
    public AdminUserService.AdminUserDetail detail(@PathVariable UUID id) {
        return users.detail(id);
    }

    @PostMapping("/{id}/ledger-adjustments")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<LedgerAdjustService.AdjustResult> adjust(@PathVariable UUID id, @RequestBody AdjustRequest req,
                                                                   @AuthenticationPrincipal AdminPrincipal actor) {
        LedgerAdjustService.AdjustResult r = adjustments.adjust(id, req.amount(), req.reason(), actor.id(), actor.role());
        return ResponseEntity.status(r.pending() ? HttpStatus.ACCEPTED : HttpStatus.CREATED).body(r);
    }
}
