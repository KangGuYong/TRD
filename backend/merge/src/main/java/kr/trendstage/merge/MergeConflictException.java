package kr.trendstage.merge;

/** 병합할 수 없는 상태. api-admin이 409로 매핑하고 type으로 사유를 구분한다(SP2 K2). */
public class MergeConflictException extends RuntimeException {

    public enum Reason {
        /** 판정 중 — 곧 풀리므로 재시도 의미 있음. */
        JUDGING("merge-judging"),
        /** 판정 완료·VOID·판정 행 존재 — 판정 후 병합(Phase 2) 전까지 영구 거부. */
        RESOLVED("merge-resolved"),
        /** 이미 다른 항목으로 병합됨. */
        TARGET_MERGED("merge-target-merged"),
        /** 큐 후보가 이미 다른 요청으로 처리됨. */
        QUEUE_DECIDED("merge-queue-decided");

        private final String type;
        Reason(String type) { this.type = type; }
    }

    private final Reason reason;

    public MergeConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() { return reason; }
    public String type() { return reason.type; }
}
