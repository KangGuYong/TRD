package kr.trendstage.apiadmin.approval;

/** 승인 상태 전이 규칙 위반(요청자 본인 승인, 중복 승인, 이미 처리된 요청 등) — 409로 매핑. */
public class ApprovalConflictException extends RuntimeException {
    public ApprovalConflictException(String message) {
        super(message);
    }
}
