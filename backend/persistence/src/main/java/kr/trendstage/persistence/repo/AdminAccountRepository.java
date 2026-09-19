package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.AdminAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
