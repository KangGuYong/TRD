package kr.trendstage.merge;

import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.VerdictRepository;
import kr.trendstage.persistence.type.TrendState;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * 병합 가능 = 판정 전 상태(DRAFT·PENDING) + 현행 판정 행 없음(SP2 K1).
 * 두 겹으로 거는 이유: 상태와 판정 행 중 한쪽만 어긋나도 흡수된 제보가 다음 판정에서 원장에 다시 실려 이중 점수가 된다.
 */
@Component
public class MergeGuard {

    private static final Set<TrendState> BEFORE_JUDGMENT = EnumSet.of(TrendState.DRAFT, TrendState.PENDING);

    private final VerdictRepository verdicts;

    public MergeGuard(VerdictRepository verdicts) {
        this.verdicts = verdicts;
    }

    public boolean isMergeable(TrendItem item) {
        return BEFORE_JUDGMENT.contains(item.getState())
                && !verdicts.existsByTrendItemIdAndSupersedesIsNull(item.getId());
    }

    public void require(TrendItem item) {
        if (item.getState() == TrendState.MERGED) {
            throw new MergeConflictException(MergeConflictException.Reason.TARGET_MERGED,
                    "이미 다른 항목으로 병합된 항목입니다");
        }
        if (item.getState() == TrendState.JUDGING) {
            throw new MergeConflictException(MergeConflictException.Reason.JUDGING,
                    "판정 중인 항목입니다 — 잠시 후 다시 시도하세요");
        }
        if (!isMergeable(item)) {
            throw new MergeConflictException(MergeConflictException.Reason.RESOLVED,
                    "이미 판정된 항목은 병합할 수 없습니다(판정 후 병합은 Phase 2)");
        }
    }
}
