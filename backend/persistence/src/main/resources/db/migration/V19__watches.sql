-- V19 · 워치(관심 키워드 추적). merge_queue(V15)와 같은 스타일.
CREATE TABLE watches (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users(id),
    keyword         VARCHAR(120)  NOT NULL,
    normalized_key  VARCHAR(160)  NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    UNIQUE (user_id, normalized_key)
);
