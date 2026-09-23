package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.type.AdminRole;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminAccountRepository extends JpaRepository<AdminAccount, UUID> {
    Optional<AdminAccount> findByLoginId(String loginId);

    /**
     * 로그인 검증용 — 행 잠금(SELECT … FOR UPDATE). 같은 계정에 동시에 들어온 실패 로그인이
     * 실패 카운터를 서로 덮어써(lost update) 잠금이 늦게 걸리는 것을 막는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AdminAccount a WHERE a.loginId = :loginId")
    Optional<AdminAccount> findByLoginIdForUpdate(@Param("loginId") String loginId);

    /** 승인 실행·sla_watch 비활성화용 — 행 잠금(SELECT … FOR UPDATE). 역할·활성 상태를 읽고 바꾸는 사이에 끼어들지 못하게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AdminAccount a WHERE a.id = :id")
    Optional<AdminAccount> findByIdForUpdate(@Param("id") UUID id);

    /**
     * 승인 자격자 수(K2) — 활성 ADMIN, 유예 경과, 특정 계정 제외. 부트스트랩 예외 판정(K6)에 쓴다.
     * role은 바인드 파라미터로 넘긴다 — JPQL에 enum 리터럴을 직접 쓰면 Hibernate가 Postgres 네이티브 enum
     * 타입명(admin_role) 대신 Java 타입명(AdminRole)으로 캐스트를 생성해 "type AdminRole does not exist"로 깨진다.
     */
    @Query("SELECT count(a) FROM AdminAccount a WHERE a.role = :role "
            + "AND a.disabledAt IS NULL AND a.activatedAt IS NOT NULL AND a.approverSince <= :now AND a.id <> :excluding")
    long countEligibleApprovers(@Param("excluding") UUID excluding, @Param("now") Instant now, @Param("role") AdminRole role);

    /**
     * sla_watch — 활성 계정 중 마지막 활동(로그인·활성화·재활성화·생성 중 가장 늦은 시각)이 cutoff 이전인 계정 id.
     * PostgreSQL GREATEST는 NULL을 건너뛴다. 판단 기준은 AdminAccount.lastActiveAt()과 같다.
     */
    @Query(value = "SELECT id FROM admin_accounts WHERE disabled_at IS NULL AND activated_at IS NOT NULL "
            + "AND GREATEST(last_login_at, activated_at, last_enabled_at, created_at) <= :cutoff", nativeQuery = true)
    List<UUID> findInactiveIdsSince(@Param("cutoff") Instant cutoff);

    /** 활성 ADMIN 수 — 마지막 ADMIN을 잠그지 않기 위해(K11). role은 바인드 파라미터로 넘긴다(위 countEligibleApprovers 참고). */
    @Query("SELECT count(a) FROM AdminAccount a WHERE a.role = :role AND a.disabledAt IS NULL AND a.activatedAt IS NOT NULL")
    long countByRoleAndDisabledAtIsNullAndActivatedAtIsNotNull(@Param("role") AdminRole role);
}
