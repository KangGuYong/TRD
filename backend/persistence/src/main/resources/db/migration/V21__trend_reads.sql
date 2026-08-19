-- V21 · 완독 기록. 카드 열람 시 기록, 재로그인/기기 교체에도 유지되도록 서버가 진실이다.
CREATE TABLE trend_reads (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL REFERENCES users(id),
    trend_item_id  UUID          NOT NULL REFERENCES trend_items(id),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, trend_item_id)
);
