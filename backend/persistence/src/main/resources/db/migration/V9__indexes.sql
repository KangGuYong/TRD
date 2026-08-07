-- V9 · 인덱스
-- 접근 패턴: 관측중 항목 배치 수집, 유저별 제보/원장 조회, 병합 후보 벡터 검색.

-- 관측 중 항목만 도는 배치(metric_collector·verdict_runner)
CREATE INDEX idx_trend_items_state        ON trend_items (state) WHERE state IN ('PENDING', 'JUDGING');
CREATE INDEX idx_trend_items_first_seen   ON trend_items (first_seen_at);
CREATE INDEX idx_trend_items_normkey      ON trend_items (normalized_key);   -- 완전일치 매칭(03 §2②)
CREATE INDEX idx_trend_items_merged_into  ON trend_items (merged_into) WHERE merged_into IS NOT NULL;

-- 병합 임베딩 유사도 검색(코사인). 벡터가 있는 행만.
CREATE INDEX idx_trend_items_embedding
    ON trend_items USING hnsw (embedding vector_cosine_ops)
    WHERE embedding IS NOT NULL;

-- 제보/동의/원장 유저·항목 조회
CREATE INDEX idx_submissions_item         ON submissions (trend_item_id);
CREATE INDEX idx_submissions_user         ON submissions (user_id, created_at DESC);
CREATE INDEX idx_submissions_pending      ON submissions (trend_item_id) WHERE result = 'PENDING';
CREATE INDEX idx_endorsements_item        ON endorsements (trend_item_id);
CREATE INDEX idx_score_ledger_user        ON score_ledger (user_id, created_at DESC);
CREATE INDEX idx_metric_snap_item_time    ON metric_snapshots (trend_item_id, captured_at DESC);
CREATE INDEX idx_votes_item               ON votes (trend_item_id);

-- 큐·거버넌스 조회
CREATE INDEX idx_abuse_flags_open         ON abuse_flags (status, detected_at) WHERE status = 'OPEN';
CREATE INDEX idx_appeals_open             ON appeals (status, created_at) WHERE status <> 'RESOLVED';
CREATE INDEX idx_sanctions_status         ON sanctions (status, created_at);
CREATE INDEX idx_approval_pending         ON approval_requests (status) WHERE status IN ('PENDING', 'PARTIAL');
CREATE INDEX idx_audit_actor_time         ON admin_audit_log (actor_id, created_at DESC);
CREATE INDEX idx_audit_target             ON admin_audit_log (target_type, target_id);
