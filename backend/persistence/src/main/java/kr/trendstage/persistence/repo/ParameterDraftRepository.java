package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ParameterDraftRepository extends JpaRepository<ParameterDraft, UUID> {
    Optional<ParameterDraft> findFirstByStatusInOrderByCreatedAtDesc(List<ParamStatus> statuses);
}
