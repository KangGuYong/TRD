package kr.trendstage.merge;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V29: 처리된 항목은 다시 큐에 들어갈 수 있고(처리 대기만 1건), 멱등키는 전역 유일하다. */
class SchemaV29Test extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void resolvedItemCanBeQueuedAgainButOnlyOnePendingPerNewItem() {
        UUID a = fx.item(T0), b = fx.item(T0), c = fx.item(T0);
        UUID first = fx.mergeQueueEntry(a, b, 0.80);
        jdbc.update("UPDATE merge_queue SET status = 'SKIPPED' WHERE id = ?", first);

        UUID again = fx.mergeQueueEntry(a, c, 0.80);   // V28까지는 전역 UNIQUE라 실패했다

        assertThat(fx.queueStatus(again)).isEqualTo("PENDING");
        assertThatThrownBy(() -> fx.mergeQueueEntry(a, b, 0.80))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void decisionKeyIsGloballyUnique() {
        UUID q1 = fx.mergeQueueEntry(fx.item(T0), fx.item(T0), 0.80);
        UUID q2 = fx.mergeQueueEntry(fx.item(T0), fx.item(T0), 0.80);
        String key = "k-" + UUID.randomUUID();
        jdbc.update("UPDATE merge_queue SET decision_key = ? WHERE id = ?", key, q1);

        assertThatThrownBy(() -> jdbc.update("UPDATE merge_queue SET decision_key = ? WHERE id = ?", key, q2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
