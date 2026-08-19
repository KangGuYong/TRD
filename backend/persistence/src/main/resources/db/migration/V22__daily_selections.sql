-- V22 · 유저별 "오늘의 5개" 하루 고정 배정. selection_date는 KST 달력 날짜.
CREATE TABLE daily_selections (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL REFERENCES users(id),
    selection_date DATE          NOT NULL,
    trend_item_id  UUID          NOT NULL REFERENCES trend_items(id),
    rank           SMALLINT      NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, selection_date, rank),
    UNIQUE (user_id, selection_date, trend_item_id)
);
