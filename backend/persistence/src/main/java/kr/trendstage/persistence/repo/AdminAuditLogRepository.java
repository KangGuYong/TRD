package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.AdminAuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLogEntry, Long> {
    Optional<AdminAuditLogEntry> findTopByOrderByIdDesc();

    /** ADM-700: ADMIN/AUDITOR 전체 조회(02 §1.1). */
    List<AdminAuditLogEntry> findTop200ByOrderByIdDesc();

    /** ADM-700: OPERATOR는 본인이 남긴 행만(02 §1.1). */
    List<AdminAuditLogEntry> findTop200ByActorIdOrderByIdDesc(UUID actorId);
}
