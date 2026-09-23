-- V29 · SP2 병합 무결성
-- 1) 결정 요청의 멱등키(Idempotency-Key). 같은 키 재요청은 이전 결과를 돌려준다 — 코드 가드가 아니라 DB 제약.
ALTER TABLE merge_queue ADD COLUMN decision_key VARCHAR(80);
ALTER TABLE merge_queue ADD CONSTRAINT merge_queue_decision_key_key UNIQUE (decision_key);
COMMENT ON COLUMN merge_queue.decision_key IS '결정 요청의 Idempotency-Key. 처리 대기 행과 V29 이전 처리 행은 NULL.';

-- 2) "새 항목당 1건"은 처리 대기 행에만. 전역 UNIQUE는 한 번 처리된 항목의 재적재를 영구히 막았다.
ALTER TABLE merge_queue DROP CONSTRAINT merge_queue_one_open_per_new;
CREATE UNIQUE INDEX merge_queue_one_pending_per_new ON merge_queue (new_trend_item_id) WHERE status = 'PENDING';
