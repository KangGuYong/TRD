package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.MetricSnapshot;
import kr.trendstage.persistence.type.MetricSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface MetricSnapshotRepository extends JpaRepository<MetricSnapshot, UUID> {

    List<MetricSnapshot> findByTrendItemIdAndCapturedAtBetween(UUID trendItemId, Instant from, Instant to);

    List<MetricSnapshot> findByTrendItemId(UUID trendItemId);

    /** 전파 도달 채널 = 관측치가 존재하는 서로 다른 소스. 홈 카드 단계·경로 표시용. */
    @Query("select distinct m.source from MetricSnapshot m where m.trendItemId = :id")
    List<MetricSource> findDistinctSources(@Param("id") UUID id);
}
