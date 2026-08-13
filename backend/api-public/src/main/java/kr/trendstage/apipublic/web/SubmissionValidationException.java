package kr.trendstage.apipublic.web;

/** 필드는 있으나 값이 계약을 어긴 경우(422) — 예: confidence가 10/30/50이 아님. */
public class SubmissionValidationException extends RuntimeException {
    public SubmissionValidationException(String message) {
        super(message);
    }
}
