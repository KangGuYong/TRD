package kr.trendstage.apipublic.web;

/** 소명 대상으로 지목되지 않은 유저의 소명 제출 시도(403). */
public class ExplanationForbiddenException extends RuntimeException {
    public ExplanationForbiddenException(String message) { super(message); }
}
