package kr.trendstage.persistence.repo;

import jakarta.persistence.LockModeType;
import kr.trendstage.persistence.entity.ApprovalRequest;
import kr.trendstage.persistence.type.ApprovalStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

    /** 승인 대기 큐(ADM-620) — PENDING만(SP3부터 PARTIAL은 쓰지 않는다). 처리 완료된 요청은 큐에서 빠진다. */
    List<ApprovalRequest> findByStatusInOrderByCreatedAtAsc(List<ApprovalStatus> statuses);

    /** 승인/반려 처리 중 행 잠금 — 두 승인자의 동시 클릭 경합 직렬화. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApprovalRequest a where a.id = :id")
    Optional<ApprovalRequest> findByIdForUpdate(@Param("id") UUID id);

    /** 항목에 대기 중인 재판정·VOID 요청이 있는지(K5). */
    boolean existsByTargetRefAndStatusAndActionTypeIn(UUID targetRef, ApprovalStatus status, Collection<String> actionTypes);
}
