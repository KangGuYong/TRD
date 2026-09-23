package kr.trendstage.apipublic.web;

/** 이번 주 제보권 소진(422, Problem.type = quota-exhausted) — J4. */
public class QuotaExhaustedException extends RuntimeException {
    public QuotaExhaustedException(String message) {
        super(message);
    }
}
