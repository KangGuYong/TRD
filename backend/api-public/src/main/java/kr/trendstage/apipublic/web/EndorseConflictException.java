package kr.trendstage.apipublic.web;

/** 이미 동의/제보한 유저가 다시 동의를 시도(409). */
public class EndorseConflictException extends RuntimeException {
    public EndorseConflictException(String message) { super(message); }
}
