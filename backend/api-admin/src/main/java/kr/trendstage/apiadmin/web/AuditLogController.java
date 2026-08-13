package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.AdminAuditLogEntry;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.AdminAuditLogRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-700 감사 로그 조회. 권한 매트릭스(02 §1.1): REVIEWER 접근 불가, OPERATOR는 본인 행만,
 * ADMIN/AUDITOR는 전체. Phase 0 스코프라 필터·페이지네이션 없이 최신 200건만 제공.
 */
@RestController
public class AuditLogController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final AdminAuditLogRepository auditLogRepository;
    private final AdminAccountRepository accountRepository;

    public AuditLogController(AdminAuditLogRepository auditLogRepository, AdminAccountRepository accountRepository) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
    }

    public record AuditLogEntryResponse(long id, String actor, String role, String action,
                                         String targetType, String targetId, String createdAt) {}

    @GetMapping("/admin/audit-log")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<AuditLogEntryResponse> list(@AuthenticationPrincipal AdminPrincipal principal) {
        List<AdminAuditLogEntry> rows = principal.role() == AdminRole.OPERATOR
                ? auditLogRepository.findTop200ByActorIdOrderByIdDesc(principal.id())
                : auditLogRepository.findTop200ByOrderByIdDesc();

        Map<UUID, String> displayNames = accountRepository.findAllById(rows.stream()
                        .map(AdminAuditLogEntry::getActorId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName));

        return rows.stream().map(row -> new AuditLogEntryResponse(
                row.getId(),
                row.getActorId() == null ? "(미인증)" : displayNames.getOrDefault(row.getActorId(), row.getActorId().toString()),
                row.getRole() == null ? "-" : row.getRole().name(),
                row.getAction(),
                row.getTargetType(),
                row.getTargetId() == null ? null : row.getTargetId().toString(),
                DISPLAY_FORMAT.format(row.getCreatedAt())
        )).toList();
    }
}
