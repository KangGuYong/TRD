package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.DailySelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DailySelectionRepository extends JpaRepository<DailySelection, UUID> {
    List<DailySelection> findByUserIdAndSelectionDateOrderByRankAsc(UUID userId, LocalDate selectionDate);
}
