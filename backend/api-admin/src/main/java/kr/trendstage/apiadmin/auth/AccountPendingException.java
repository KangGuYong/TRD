package kr.trendstage.apiadmin.auth;

/** 계정 생성 승인 대기 — 로그인 불가(403). */
public class AccountPendingException extends RuntimeException {
    public AccountPendingException(String message) { super(message); }
}
