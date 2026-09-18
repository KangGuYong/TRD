package kr.trendstage.admin;

import kr.trendstage.apiadmin.queues.QueueSummaryService;
import kr.trendstage.apiadmin.queues.QueueSummaryService.QueueSummaryResponse;
import kr.trendstage.apiadmin.queues.QueueSummaryService.QueueTile;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QueueSummaryTest extends AbstractIntegrationTest {

    @Autowired QueueSummaryService service;

    private UUID userId;
    private UUID trendItemId;
    private final Instant now = Instant.parse("2026-09-18T12:00:00Z");

    @BeforeEach
    void setUp() {
        clock.set(now);
        jdbc.update("DELETE FROM reports");
        userId = UUID.randomUUID();
        trendItemId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, handle) VALUES (?, ?)", userId, "qs-" + userId);
        jdbc.update("INSERT INTO trend_items (id, canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, ?, 'MEME'::trend_category, 'PENDING'::trend_state, ?)",
                trendItemId, "큐요약", "qs-" + trendItemId, Timestamp.from(now));
    }

    private void report(String status, Duration age) {
        jdbc.update("INSERT INTO reports (trend_item_id, reporter_id, reason, status, created_at) "
                        + "VALUES (?, ?, 'OTHER'::report_reason, ?::report_status, ?)",
                trendItemId, userId, status, Timestamp.from(now.minus(age)));
    }

    private QueueTile tile(QueueSummaryResponse r, String id) {
        return r.queues().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void reportQueueCountsOpenAndExplaining_excludesDecided() {
        report("OPEN", Duration.ofHours(1));
        report("EXPLAINING", Duration.ofHours(10));
        report("DECIDED", Duration.ofHours(1));

        QueueTile t = tile(service.summarize(), "ADM-410");

        assertThat(t.count()).isEqualTo(2);
        assertThat(t.available()).isTrue();
        assertThat(t.slaExceeded()).isFalse();
        assertThat(t.oldest()).isEqualTo("1h");
    }

    @Test
    void openReportOlderThan4hBreachesSla() {
        report("OPEN", Duration.ofHours(5));

        QueueSummaryResponse r = service.summarize();
        QueueTile t = tile(r, "ADM-410");

        assertThat(t.slaExceeded()).isTrue();
        assertThat(r.slaBreaches()).isGreaterThanOrEqualTo(1);
        assertThat(r.alerts()).anyMatch(a -> a.title().startsWith("신고 콘텐츠 SLA 초과"));
    }

    @Test
    void explainingOlderThan4hDoesNotBreachSla() {
        report("EXPLAINING", Duration.ofHours(30));

        assertThat(tile(service.summarize(), "ADM-410").slaExceeded()).isFalse();
    }

    @Test
    void unimplementedQueuesAreMarkedUnavailable() {
        QueueSummaryResponse r = service.summarize();

        assertThat(tile(r, "ADM-300").available()).isFalse();
        assertThat(tile(r, "ADM-400").available()).isFalse();
        assertThat(tile(r, "ADM-100").available()).isTrue();
    }
}
