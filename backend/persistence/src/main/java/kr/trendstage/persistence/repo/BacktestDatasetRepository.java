package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.BacktestDataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BacktestDatasetRepository extends JpaRepository<BacktestDataset, UUID> {
    Optional<BacktestDataset> findBySha256(String sha256);

    List<BacktestDataset> findAllByOrderByCreatedAtDesc();
}
