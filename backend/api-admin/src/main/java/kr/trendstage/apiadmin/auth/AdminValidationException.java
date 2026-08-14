package kr.trendstage.apiadmin.auth;

public class AdminValidationException extends RuntimeException {
    public AdminValidationException(String message) {
        super(message);
    }
}
