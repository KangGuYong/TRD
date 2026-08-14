package kr.trendstage.apiadmin.auth;

public class DraftLockedException extends RuntimeException {
    public DraftLockedException(String message) {
        super(message);
    }
}
