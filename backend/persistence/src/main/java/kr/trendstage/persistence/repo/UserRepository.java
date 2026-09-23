package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserAccount, UUID> {
    Optional<UserAccount> findByHandle(String handle);
    Optional<UserAccount> findByFirebaseUid(String firebaseUid);

    /** ADM-311 진입 — 핸들 앞부분 일치(대소문자 무시), 최대 20건. */
    List<UserAccount> findTop20ByHandleStartingWithIgnoreCaseOrderByHandleAsc(String prefix);

    /** 같은 유저의 동시 제보를 직렬화 — 제보권 확인과 저장 사이에 다른 제보가 끼지 못하게(J4). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") UUID id);
}
