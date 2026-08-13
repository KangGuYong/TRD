package kr.trendstage.apipublic.web;

import java.util.UUID;

/** 같은 유저가 같은 트렌드 항목에 이미 유효 제보를 낸 경우(409) — 동의(endorse)로 전환 안내. */
public class DuplicateSubmissionException extends RuntimeException {
    private final UUID trendItemId;
    private final int dupeRank;

    public DuplicateSubmissionException(UUID trendItemId, int dupeRank, String message) {
        super(message);
        this.trendItemId = trendItemId;
        this.dupeRank = dupeRank;
    }

    public UUID getTrendItemId() { return trendItemId; }
    public int getDupeRank() { return dupeRank; }
}
