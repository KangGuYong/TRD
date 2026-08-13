package kr.trendstage.audit;

import jakarta.persistence.EntityManager;
import kr.trendstage.persistence.entity.AdminAuditLogEntry;
import kr.trendstage.persistence.repo.AdminAuditLogRepository;
import kr.trendstage.persistence.type.AdminRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 감사 로그 기록 공용 서비스. ADM-100/200/600 등 이후 화면의 모든 관리자 액션이 이 서비스를 통해
 * admin_audit_log에 append한다. 동시 쓰기로 해시체인이 분기되지 않도록 advisory lock으로 직렬화한다
 * (V7 트리거는 UPDATE/DELETE만 막을 뿐, 동시 INSERT 순서 경합까지는 막지 않는다).
 */
@Service
public class AuditLogService {

    /** admin_audit_log 쓰기 직렬화용 고정 advisory lock 키. 임의의 상수. */
    private static final long AUDIT_LOG_LOCK_KEY = 872_301_445L;

    private final AdminAuditLogRepository repository;
    private final EntityManager entityManager;
    private final Clock clock;

    public AuditLogService(AdminAuditLogRepository repository, EntityManager entityManager, Clock clock) {
        this.repository = repository;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // REQUIRES_NEW: 호출자 트랜잭션(예: 로그인 실패 후 예외로 롤백)과 무관하게 감사 기록은 항상 커밋돼야 한다.
    // 그렇지 않으면 실패 이벤트(LOGIN_FAILED 등)가 롤백과 함께 사라져 감사 로그의 존재 이유가 무너진다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AdminAuditLogEntry record(UUID actorId, AdminRole role, String action, String targetType,
                                      UUID targetId, Map<String, ?> detail) {
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", AUDIT_LOG_LOCK_KEY)
                .getSingleResult();

        byte[] prevHash = repository.findTopByOrderByIdDesc()
                .map(AdminAuditLogEntry::getHash)
                .orElse(null);

        Instant createdAt = clock.instant();
        String canonicalDetail = HashChainer.canonicalDetailJson(detail);
        byte[] hash = HashChainer.computeHash(prevHash,
                actorId == null ? null : actorId.toString(),
                role == null ? null : role.name(),
                action,
                targetType,
                targetId == null ? null : targetId.toString(),
                canonicalDetail,
                createdAt);

        AdminAuditLogEntry entry = AdminAuditLogEntry.of(actorId, role, action, targetType, targetId,
                canonicalDetail, prevHash, hash, createdAt);
        return repository.save(entry);
    }
}
