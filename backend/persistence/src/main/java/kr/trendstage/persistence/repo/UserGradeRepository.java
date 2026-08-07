package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.UserGrade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserGradeRepository extends JpaRepository<UserGrade, UUID> {

    /** 최신 등급 스냅샷 (뷰 user_grade_current 대응). */
    Optional<UserGrade> findTopByUserIdOrderByComputedAtDesc(UUID userId);
}
