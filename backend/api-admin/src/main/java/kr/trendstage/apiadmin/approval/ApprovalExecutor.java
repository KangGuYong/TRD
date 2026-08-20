package kr.trendstage.apiadmin.approval;

import kr.trendstage.persistence.entity.ApprovalRequest;

/**
 * approval_requests.action_type별 실행 로직. B3(제재)/B4(등급조정·원장조정)는 각자 이 인터페이스를
 * 구현하는 @Component만 추가하면 ApprovalService 수정 없이 등록된다.
 */
public interface ApprovalExecutor {
    /** approval_requests.action_type과 정확히 일치해야 한다(예: "PARAM_APPLY"). */
    String actionType();

    /** 2/2 승인 시 호출. 예외를 던지면 승인 트랜잭션 전체가 롤백된다(승인 요청은 PARTIAL로 남아 재시도 가능). */
    void execute(ApprovalRequest request);

    /** 반려 시 대상 리소스를 원복. 기본은 아무 것도 하지 않음(대상이 없거나 원복이 불필요한 액션타입). */
    default void onReject(ApprovalRequest request) {}
}
