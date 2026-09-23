package kr.trendstage.apiadmin.merge;

/** 같은 키로 다른 결정을 보냈거나, 키가 다른 후보에 이미 쓰임 → 422 idempotency-key-mismatch. */
public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException(String message) {
        super(message);
    }
}
