package kr.trendstage.judge;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * 재판정·항목 VOID가 만드는 원장 변동(ADJ)을 어떻게 다룰지(SP3 §3.1). 판정 모듈은 승인을 모른다 —
 * 한도를 넘는지만 알려 주고, 승인 실행이면 ADJ 행에 승인 정보를 남긴다.
 *
 * @param limit      차액 절댓값 합의 한도. null = 한도 없음(승인된 실행)
 * @param approvalId 승인 요청 id(승인 실행일 때만)
 * @param approvedBy 승인 관리자 id(승인 실행일 때만)
 */
public record AdjustmentPolicy(BigDecimal limit, UUID approvalId, UUID approvedBy) {

    public static AdjustmentPolicy limitedTo(BigDecimal limit) {
        return new AdjustmentPolicy(Objects.requireNonNull(limit), null, null);
    }

    public static AdjustmentPolicy approved(UUID approvalId, UUID approvedBy) {
        return new AdjustmentPolicy(null, Objects.requireNonNull(approvalId), Objects.requireNonNull(approvedBy));
    }

    /** 한도를 넘는가 — 정확히 한도와 같으면 통과(K3). */
    public boolean exceededBy(BigDecimal total) {
        return limit != null && total.compareTo(limit) > 0;
    }
}
