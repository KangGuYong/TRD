package kr.trendstage.persistence.type;

/** trend_items.state (PG enum trend_state). MERGED = 병합 패자 tombstone. */
public enum TrendState { DRAFT, PENDING, JUDGING, RESOLVED, VOID, MERGED }
