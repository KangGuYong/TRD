package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.ApprovalRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {}
