package kr.trendstage.apiadmin.auth;

/** 연속 로그인 실패로 잠긴 계정. 423으로 매핑된다(AdminApiExceptionHandler). */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
