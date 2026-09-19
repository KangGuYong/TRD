-- V28 · SP1 판정 파이프라인 정합성 (docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md)

-- 0) 초기화 확인(J7). 감쇠가 곱해진 옛 원장·옛 판정 근거 형식과 섞이지 않게 한다.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM verdicts) OR EXISTS (SELECT 1 FROM score_ledger) THEN
        RAISE EXCEPTION 'SP1(V28): 기존 판정·원장이 남아 있습니다. 개발 DB를 초기화하세요 (docs/superpowers/specs/2026-09-20-sp1-verdict-pipeline-design.md §0)';
    END IF;
END $$;

-- 1) 원장 행별 감쇠 기준(J1, O11=(b)). 0)에서 비어 있음을 확인했으므로 기본값 없이 NOT NULL.
ALTER TABLE score_ledger
    ADD COLUMN halflife_days   INTEGER     NOT NULL CHECK (halflife_days > 0),
    ADD COLUMN decay_anchor_at TIMESTAMPTZ NOT NULL;
COMMENT ON COLUMN score_ledger.delta IS '원값 점수(감쇠 없음). 감쇠는 AS 조회 때 halflife_days·decay_anchor_at으로 계산한다.';
COMMENT ON COLUMN score_ledger.halflife_days IS '이 행을 기록할 때의 반감기(일). 파라미터가 바뀌어도 과거 행의 감쇠는 그대로다(비소급).';
COMMENT ON COLUMN score_ledger.decay_anchor_at IS '감쇠 기준 시각. 판정 행은 판정 시각, 재판정·VOID 차액은 원 판정 시각.';

-- 2) 멱등: 한 판정이 한 제보에 원장을 두 번 남기지 않는다. 수동 ADJ(verdict NULL)는 영향 없음.
ALTER TABLE score_ledger
    ADD CONSTRAINT ledger_one_row_per_verdict_submission UNIQUE (verdict_id, submission_id);

-- 3) 재판정 체인은 한 줄 — 같은 판정을 두 판정이 대체하지 못한다.
CREATE UNIQUE INDEX verdict_superseded_once ON verdicts (supersedes) WHERE supersedes IS NOT NULL;

-- 4) 제보: VOID 시각(제보권 반환 기준, J4)·처음 판정 시각(TI 180일 창 기준)
ALTER TABLE submissions
    ADD COLUMN voided_at   TIMESTAMPTZ,
    ADD COLUMN resolved_at TIMESTAMPTZ;
UPDATE submissions SET voided_at   = created_at WHERE result = 'VOID';
UPDATE submissions SET resolved_at = created_at WHERE result IN ('HIT', 'MISS');
ALTER TABLE submissions
    ADD CONSTRAINT submission_void_has_time   CHECK ((result = 'VOID') = (voided_at IS NOT NULL)),
    ADD CONSTRAINT submission_judged_has_time CHECK (result NOT IN ('HIT', 'MISS') OR resolved_at IS NOT NULL);
CREATE INDEX idx_submissions_user_voided   ON submissions (user_id, voided_at)   WHERE voided_at IS NOT NULL;
CREATE INDEX idx_submissions_user_resolved ON submissions (user_id, resolved_at) WHERE resolved_at IS NOT NULL;
COMMENT ON COLUMN submissions.voided_at IS 'VOID된 시각. 이 시각이 속한 주에 제보권 한 장이 반환된다.';
COMMENT ON COLUMN submissions.resolved_at IS '처음 HIT/MISS로 판정된 시각(재판정해도 유지). TI 180일 창의 기준.';

-- 5) 선점 순위에서 시딩 제외(J3). 시딩은 클러스터 앵커 역할만 한다.
CREATE OR REPLACE VIEW submission_order_rank AS
SELECT id,
       trend_item_id,
       CAST(RANK() OVER (PARTITION BY trend_item_id ORDER BY created_at ASC) AS integer) AS order_rank
FROM submissions
WHERE result <> 'VOID' AND NOT is_seed;
COMMENT ON VIEW submission_order_rank IS '제보 선점 순위. 저장 컬럼이 아니라 submissions.created_at 기준으로 매번 계산되는 파생 뷰(RANK() OVER, VOID·시딩 제외). 병합이 일어나도 전체가 자동 재정렬된다.';
