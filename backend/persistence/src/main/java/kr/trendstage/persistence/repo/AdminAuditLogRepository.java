package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.AdminAuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLogEntry, Long>,
        JpaSpecificationExecutor<AdminAuditLogEntry> {
    Optional<AdminAuditLogEntry> findTopByOrderByIdDesc();
}
