package kr.trendstage.persistence.trend;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendCategory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 새 항목 생성. 같은 키가 동시에 처음 들어와도 500 없이 먼저 생긴 항목에 합류한다(SP2 §4.2).
 * INSERT … ON CONFLICT DO NOTHING은 예외를 던지지 않아 호출 트랜잭션을 abort시키지 않는다
 * (PostgreSQL에서 UNIQUE 위반 예외는 트랜잭션 전체를 실패 상태로 만든다).
 */
@Component
public class TrendItemCreator {

    /** created = 이 호출이 새로 만들었음. false면 먼저 생긴 항목에 합류 — 호출 측이 마감·중복 검사를 해야 한다. */
    public record Result(TrendItem item, boolean created) {}

    private final JdbcTemplate jdbc;
    private final TrendItemRepository trendItems;
    private final TrendItemLookup lookup;

    public TrendItemCreator(JdbcTemplate jdbc, TrendItemRepository trendItems, TrendItemLookup lookup) {
        this.jdbc = jdbc;
        this.trendItems = trendItems;
        this.lookup = lookup;
    }

    public Result createOrJoin(String canonicalName, String normalizedKey, TrendCategory category, Instant firstSeenAt) {
        List<UUID> inserted = jdbc.queryForList(
                "INSERT INTO trend_items (canonical_name, normalized_key, category, state, first_seen_at) "
                        + "VALUES (?, ?, ?::trend_category, 'PENDING', ?) "
                        + "ON CONFLICT (normalized_key) DO NOTHING RETURNING id",
                UUID.class, canonicalName, normalizedKey, category.name(), Timestamp.from(firstSeenAt));
        if (!inserted.isEmpty()) {
            return new Result(trendItems.findById(inserted.get(0)).orElseThrow(), true);
        }
        TrendItem existing = lookup.findLiveByNormalizedKey(normalizedKey)
                .orElseThrow(() -> new IllegalStateException("키 충돌 뒤 항목을 찾지 못했습니다: " + normalizedKey));
        return new Result(existing, false);
    }
}
