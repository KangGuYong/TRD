-- V15 · 임베딩 유사도 병합 큐 (03 §2③④, ADM-100)
-- 0.85 이상은 cluster_merge 배치가 자동 병합. 0.75~0.85는 사람 검수(회색지대) — 이 테이블이 그 큐.
-- 0.85 미만은 큐에 넣지 않는다.

CREATE TYPE merge_queue_status AS ENUM ('PENDING', 'MERGED', 'VOIDED', 'SKIPPED');

CREATE TABLE merge_queue (
    id                UUID               PRIMARY KEY DEFAULT gen_random_uuid(),
    new_trend_item_id UUID               NOT NULL REFERENCES trend_items(id),
    old_trend_item_id UUID               NOT NULL REFERENCES trend_items(id),
    similarity        NUMERIC(5,4)       NOT NULL,
    status            merge_queue_status NOT NULL DEFAULT 'PENDING',
    created_at        TIMESTAMPTZ        NOT NULL DEFAULT now(),
    resolved_at       TIMESTAMPTZ,
    resolved_by       UUID               REFERENCES admin_accounts(id),
    -- 신규 항목당 열린 큐 항목은 하나뿐 — 배치 재실행 시 중복 적재 방지(멱등성).
    CONSTRAINT merge_queue_one_open_per_new UNIQUE (new_trend_item_id)
);

-- cluster_merge 배치가 "이미 검토한 항목"을 매일 다시 비교하지 않도록 하는 커서.
-- NULL이면 아직 임베딩 유사도 비교 전(자동병합/큐적재/스킵 어느 쪽도 결정 안 됨).
ALTER TABLE trend_items ADD COLUMN merge_checked_at TIMESTAMPTZ;
