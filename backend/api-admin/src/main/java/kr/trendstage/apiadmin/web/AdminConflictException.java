package kr.trendstage.apiadmin.web;

/** 409 + type. 콘솔이 type으로 안내를 나눈다(approval-pending, void-needs-approval 등). */
public class AdminConflictException extends RuntimeException {
    private final String type;

    public AdminConflictException(String type, String message) {
        super(message);
        this.type = type;
    }

    public String type() { return type; }
}
