package kr.trendstage.apiadmin.approval;

import kr.trendstage.persistence.entity.ApprovalRequest;

import java.util.Map;

/**
 * approval_requests.action_type별 실행 로직. 구현 @Component를 추가하면 ApprovalService가 자동 등록한다.
 */
public interface ApprovalExecutor {
    /** approval_requests.action_type과 정확히 일치(ActionType.name()). */
    String actionType();

    /**
     * 승인 시 호출. 반환값은 APPROVAL_EXECUTE 감사 로그 detail에 더해진다(예상·실제 차액 등).
     * 예외를 던지면 승인 트랜잭션 전체가 롤백된다 — 요청은 PENDING으로 남아 재시도·반려할 수 있다.
     */
    Map<String, String> execute(ApprovalRequest request);

    /** 반려 시 대상 리소스 원복. 기본은 없음. */
    default void onReject(ApprovalRequest request) {}

    /** 승인 화면 요약 한 줄. */
    default String describe(ApprovalRequest request) { return actionType(); }
}
