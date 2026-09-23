package kr.trendstage.judge;

/** 상태 충돌(이미 VOID·병합된 항목, 재판정 체인 경합). 콘솔 API는 409로 매핑한다. */
public class JudgeConflictException extends RuntimeException {
    public JudgeConflictException(String message) {
        super(message);
    }
}
