package kr.trendstage.apiadmin.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.AdminAuditLogEntry;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.repo.AdminAuditLogRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ADM-700 감사 로그 조회. 권한 매트릭스(02 §1.1): REVIEWER 접근 불가, OPERATOR는 본인 행만,
 * ADMIN/AUDITOR는 전체. 필터·커서(100건), detail 포함.
 */
@RestController
public class AuditLogController {

    private static final DateTimeFormatter DISPLAY_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    static final int PAGE = 100;

    private final AdminAuditLogRepository auditLogRepository;
    private final AdminAccountRepository accountRepository;
    private final ObjectMapper objectMapper;

    public AuditLogController(AdminAuditLogRepository auditLogRepository, AdminAccountRepository accountRepository,
                               ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
        this.objectMapper = objectMapper;
    }

    public record AuditLogEntryResponse(long id, String actor, String role, String action, String targetType,
                                        String targetId, Map<String, Object> detail, String createdAt) {}
    public record AuditLogPage(List<AuditLogEntryResponse> items, Long nextBeforeId) {}

    @GetMapping("/admin/audit-log")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public AuditLogPage list(@AuthenticationPrincipal AdminPrincipal principal,
                             @RequestParam(required = false) String action,
                             @RequestParam(required = false) UUID actorId,
                             @RequestParam(required = false) UUID targetId,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to,
                             @RequestParam(required = false) Long beforeId) {
        UUID actor = principal.role() == AdminRole.OPERATOR ? principal.id() : actorId;   // OPERATOR는 본인 행만
        Specification<AdminAuditLogEntry> spec = Specification.allOf(
                eq("action", action), eq("actorId", actor), eq("targetId", targetId),
                ge("createdAt", parse(from)), lt("createdAt", parse(to)), ltId(beforeId));
        List<AdminAuditLogEntry> rows = auditLogRepository.findAll(spec,
                PageRequest.of(0, PAGE + 1, Sort.by(Sort.Direction.DESC, "id"))).getContent();
        boolean more = rows.size() > PAGE;
        List<AdminAuditLogEntry> page = more ? rows.subList(0, PAGE) : rows;
        Map<UUID, String> names = displayNames(page);
        return new AuditLogPage(page.stream().map(r -> toResponse(r, names)).toList(),
                more ? page.get(page.size() - 1).getId() : null);
    }

    private static <T> Specification<AdminAuditLogEntry> eq(String field, T value) {
        return value == null ? null : (root, q, cb) -> cb.equal(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> ge(String field, Instant value) {
        return value == null ? null : (root, q, cb) -> cb.greaterThanOrEqualTo(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> lt(String field, Instant value) {
        return value == null ? null : (root, q, cb) -> cb.lessThan(root.get(field), value);
    }

    private static Specification<AdminAuditLogEntry> ltId(Long beforeId) {
        return beforeId == null ? null : (root, q, cb) -> cb.lessThan(root.get("id"), beforeId);
    }

    private static Instant parse(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            throw new AdminValidationException("날짜는 ISO-8601 형식이어야 합니다(예: 2026-09-23T00:00:00Z): " + iso);
        }
    }

    private Map<UUID, String> displayNames(List<AdminAuditLogEntry> rows) {
        return accountRepository.findAllById(rows.stream()
                        .map(AdminAuditLogEntry::getActorId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(AdminAccount::getId, AdminAccount::getDisplayName));
    }

    private AuditLogEntryResponse toResponse(AdminAuditLogEntry row, Map<UUID, String> names) {
        String actor = row.getActorId() == null
                ? (row.getAction().startsWith("SLA_") ? "(시스템)" : "(미인증)")
                : names.getOrDefault(row.getActorId(), row.getActorId().toString());
        return new AuditLogEntryResponse(row.getId(), actor, row.getRole() == null ? "-" : row.getRole().name(),
                row.getAction(), row.getTargetType(), row.getTargetId() == null ? null : row.getTargetId().toString(),
                readDetail(row.getDetail()), DISPLAY_FORMAT.format(row.getCreatedAt()));
    }

    private Map<String, Object> readDetail(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
