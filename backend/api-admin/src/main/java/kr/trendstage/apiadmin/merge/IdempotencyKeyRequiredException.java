package kr.trendstage.apiadmin.merge;

/** 결정 요청에 Idempotency-Key가 없거나 형식이 틀림 → 400 idempotency-key-required. */
public class IdempotencyKeyRequiredException extends RuntimeException {
    public IdempotencyKeyRequiredException(String message) {
        super(message);
    }
}
