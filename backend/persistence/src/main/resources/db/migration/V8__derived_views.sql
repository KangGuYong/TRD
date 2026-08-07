-- V8 · 파생 뷰 (order_rank, 최신 등급)

-- ── 선점 순위(order_rank) ────────────────────────────────────────────
-- 저장 컬럼이 아니라 created_at 기준 파생값. 병합마다 자동 재정렬된다(03 §3.1).
-- 판정 시점에는 이 값을 verdicts.evidence_json으로 스냅샷 동결하므로 확정점수는 흔들리지 않는다.
CREATE VIEW submission_order_rank AS
SELECT
    id,
    trend_item_id,
    RANK() OVER (PARTITION BY trend_item_id ORDER BY created_at ASC) AS order_rank
FROM submissions
WHERE result <> 'VOID';

-- ── 유저별 최신 등급 스냅샷 ──────────────────────────────────────────
-- user_grades는 이력(append-only)이므로, 현재 등급은 가장 최근 computed_at 행.
CREATE VIEW user_grade_current AS
SELECT DISTINCT ON (user_id)
    user_id, grade, trust_index, active_score, judged_count, computed_at
FROM user_grades
ORDER BY user_id, computed_at DESC;
