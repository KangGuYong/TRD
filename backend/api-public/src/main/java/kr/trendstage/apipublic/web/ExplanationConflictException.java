package kr.trendstage.apipublic.web;

/** EXPLAINING 상태가 아닌 신고에 소명을 제출하려는 시도(409). */
public class ExplanationConflictException extends RuntimeException {
    public ExplanationConflictException(String message) { super(message); }
}
