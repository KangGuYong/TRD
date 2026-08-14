package kr.trendstage.apiadmin.auth;

public class DuplicateLoginIdException extends RuntimeException {
    public DuplicateLoginIdException(String message) {
        super(message);
    }
}
