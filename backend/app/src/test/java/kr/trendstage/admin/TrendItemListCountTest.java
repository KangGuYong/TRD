package kr.trendstage.admin;

import kr.trendstage.apiadmin.trend.TrendItemAdminService;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADM-110 목록의 '제보자수'는 판정 신호·ADM-111 상세와 같은 기준이어야 한다.
 * 서로 다른 제보자만 세고, 시딩과 VOID는 뺀다(SP1 스펙 §0 2번).
 */
class TrendItemListCountTest extends AbstractIntegrationTest {

    private static final Instant FIRST_SEEN = Instant.parse("2026-08-01T00:00:00Z");

    @Autowired TrendItemAdminService trendItems;

    @Test
    void listCountsDistinctSubmittersExcludingSeedAndVoid() {
        UUID item = fx.item(FIRST_SEEN);
        UUID u1 = fx.user();
        fx.seedSubmission(fx.user(), item, FIRST_SEEN);              // 시딩 — 제외
        fx.submission(u1, item, 30, FIRST_SEEN.plusSeconds(1));
        fx.submission(u1, item, 10, FIRST_SEEN.plusSeconds(2));      // 같은 유저 — 1명으로
        fx.submission(fx.user(), item, 50, FIRST_SEEN.plusSeconds(3));
        UUID voided = fx.submission(fx.user(), item, 30, FIRST_SEEN.plusSeconds(4));
        fx.voidSubmission(voided, FIRST_SEEN.plusSeconds(5));        // VOID — 제외

        int count = trendItems.list().stream()
                .filter(s -> s.id().equals(item.toString()))
                .findFirst().orElseThrow()
                .submitterCount();

        assertThat(count).isEqualTo(2);
    }
}
