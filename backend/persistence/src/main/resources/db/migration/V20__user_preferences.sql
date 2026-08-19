-- V20 · 관심 카테고리 · 알림 시간. 1인 1행(append-only 대상 아님 — 원장/판정과 달리 "현재값"만 의미 있음).
CREATE TABLE user_preferences (
    user_id      UUID          PRIMARY KEY REFERENCES users(id),
    categories   TEXT[]        NOT NULL,
    notify_hour  SMALLINT      NOT NULL CHECK (notify_hour BETWEEN 0 AND 23),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);
