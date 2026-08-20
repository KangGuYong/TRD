package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParameterDraftRepository extends JpaRepository<ParameterDraft, UUID> {
    Optional<ParameterDraft> findFirstByStatusInOrderByCreatedAtDesc(List<ParamStatus> statuses);

    /** 운영에 반영된 최신 파라미터 — CurrentParameterSetResolver가 사용. */
    Optional<ParameterDraft> findFirstByStatusOrderByAppliedAtDesc(ParamStatus status);
}
