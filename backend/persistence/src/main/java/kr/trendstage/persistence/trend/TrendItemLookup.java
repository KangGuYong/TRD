package kr.trendstage.persistence.trend;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.TrendItemRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 완전일치 조회가 병합된 항목(tombstone)을 만나면 merged_into를 따라 살아 있는 승자까지 간다(SP2 K6).
 * 패자의 normalized_key는 watches FK가 참조해 비울 수 없으므로(V24) 조회 쪽에서 따라간다.
 * 제보·워치·시딩이 이 컴포넌트 하나를 쓴다.
 */
@Component
public class TrendItemLookup {

    static final int MAX_HOPS = 10;

    private final TrendItemRepository trendItems;

    public TrendItemLookup(TrendItemRepository trendItems) {
        this.trendItems = trendItems;
    }

    public Optional<TrendItem> findLiveByNormalizedKey(String normalizedKey) {
        return trendItems.findByNormalizedKey(normalizedKey).map(this::followToLive);
    }

    /** 순환이나 비정상적으로 긴 체인은 데이터 손상이다 — 조용히 넘기지 않는다. */
    public TrendItem followToLive(TrendItem item) {
        TrendItem current = item;
        Set<UUID> seen = new HashSet<>();
        while (current.getState() == TrendState.MERGED) {
            if (!seen.add(current.getId()) || seen.size() > MAX_HOPS) {
                throw new IllegalStateException("병합 체인이 순환하거나 너무 깁니다: " + item.getId());
            }
            UUID next = current.getMergedInto();
            current = trendItems.findById(next)
                    .orElseThrow(() -> new IllegalStateException("병합 승자가 없습니다: " + next));
        }
        return current;
    }
}
