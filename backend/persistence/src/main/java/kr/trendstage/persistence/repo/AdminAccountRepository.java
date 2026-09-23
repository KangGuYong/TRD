package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.AdminAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

    /** 승인 자격자 수(K2) — 활성 ADMIN, 유예 경과, 특정 계정 제외. 부트스트랩 예외 판정(K6)에 쓴다. */
    @Query("SELECT count(a) FROM AdminAccount a WHERE a.role = kr.trendstage.persistence.type.AdminRole.ADMIN "
            + "AND a.disabledAt IS NULL AND a.activatedAt IS NOT NULL AND a.approverSince <= :now AND a.id <> :excluding")
    long countEligibleApprovers(@Param("excluding") UUID excluding, @Param("now") Instant now);
}
