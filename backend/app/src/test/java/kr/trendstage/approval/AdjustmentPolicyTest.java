package kr.trendstage.approval;

import kr.trendstage.judge.AdjustmentPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** K3 경계 — 정확히 100은 통과, 그보다 크면 승인. 승인 실행은 한도가 없다. */
class AdjustmentPolicyTest {

    @Test
    void exactly100PassesAndAboveNeedsApproval() {
        AdjustmentPolicy p = AdjustmentPolicy.limitedTo(new BigDecimal("100"));
        assertThat(p.exceededBy(new BigDecimal("100.0000"))).isFalse();
        assertThat(p.exceededBy(new BigDecimal("100.0001"))).isTrue();
        assertThat(p.exceededBy(BigDecimal.ZERO)).isFalse();
    }

    @Test
    void approvedExecutionHasNoLimit() {
        AdjustmentPolicy p = AdjustmentPolicy.approved(UUID.randomUUID(), UUID.randomUUID());
        assertThat(p.exceededBy(new BigDecimal("99999"))).isFalse();
    }
}
