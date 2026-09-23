package kr.trendstage.judge;

import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.persistence.entity.Verdict;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 재판정·항목 VOID의 결과. 예외가 아니라 값으로 돌려준다 — 예외가 @Transactional 프록시를 지나면
 * 호출 측 트랜잭션이 rollback-only가 되어 승인 요청을 저장할 수 없다(SP3 §3.1).
 */
public sealed interface JudgeOutcome {

    /** 반영됨. verdict는 새 판정(판정 전 항목의 VOID면 비어 있음). adjTotal = 기록한 ADJ 절댓값 합. */
    record Applied(Optional<Verdict> verdict, BigDecimal adjTotal) implements JudgeOutcome {}

    /** 한도 초과 — 아무것도 쓰지 않았다. */
    record NeedsApproval(BigDecimal adjTotal, VerdictResult expectedResult) implements JudgeOutcome {}
}
