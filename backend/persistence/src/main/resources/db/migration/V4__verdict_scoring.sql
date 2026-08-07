-- V4 · 판정 · 점수 원장 · 등급 · 투표
-- verdicts와 score_ledger는 append-only(R2). UPDATE/DELETE 차단 트리거는 V7.

-- ── 판정 결과 ────────────────────────────────────────────────────────
-- verdict_runner 배치만 INSERT한다. 관리자는 결과를 바꾸지 못한다(R1, 02 ADM-200 금지기능).
-- 재판정은 새 행을 supersedes로 쌓고, 점수 차액은 score_ledger의 ADJ로 반영(02 §ADM-200).
CREATE TABLE verdicts (
    id            UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id UUID           NOT NULL REFERENCES trend_items(id),
    result        verdict_result NOT NULL,
    reach_level   reach_level,                 -- HIT만 값. MISS/VOID는 NULL
    score_t       NUMERIC(5,4),                -- 종합점수 T (0~1). VOID면 NULL 가능
    judged_at     TIMESTAMPTZ    NOT NULL,
    -- 판정 시점 order_rank·baseline 스냅샷 동결(03 §3.1). 이후 병합돼도 확정점수 불변.
    evidence_json JSONB          NOT NULL,
    supersedes    UUID           REFERENCES verdicts(id), -- 재판정 시 이전 판정 참조
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- HIT일 때만 reach_level 존재
    CONSTRAINT verdict_reach_consistency
        CHECK ((result = 'HIT') = (reach_level IS NOT NULL))
);

-- 재실행 멱등: verdict_runner가 만드는 '원본' 판정은 항목당 하나만(supersedes IS NULL).
-- 재판정은 supersedes를 채운 새 행으로 쌓이므로 이 인덱스에 걸리지 않는다.
-- → 배치가 두 번 돌아도 원본 판정이 중복 생성되지 않아 점수를 두 번 주지 않는다(04 §6).
CREATE UNIQUE INDEX verdict_one_original_per_item
    ON verdicts (trend_item_id)
    WHERE supersedes IS NULL;

-- ── 점수 원장(append-only) ───────────────────────────────────────────
-- HIT: +c·w_order·(1+m)·d / MISS: −c·0.5·d / VOID: 0 (01 §5.1)
-- 정정은 원본 수정이 아니라 ADJ 상쇄행 추가뿐(R2).
CREATE TABLE score_ledger (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id),
    submission_id UUID        REFERENCES submissions(id),  -- ADJ 행은 제보와 무관할 수 있어 NULL 허용
    verdict_id    UUID        REFERENCES verdicts(id),
    kind          ledger_kind NOT NULL,
    delta         NUMERIC(10,4) NOT NULL,
    reason        TEXT        NOT NULL,                    -- 산정 근거 문자열(예: '#1204 order1 c50 m1.0')
    approved_by   UUID        REFERENCES users(id),        -- ADJ·100점 초과는 2인 승인자 표시(02 §ADM-311)
    approval_id   UUID,                                    -- V6 approval_requests 참조(2인 승인 연결)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- VOID는 점수 변동 없음
    CONSTRAINT ledger_void_zero CHECK (kind <> 'VOID' OR delta = 0),
    -- ADJ에는 반드시 사유가 있어야 함(빈 문자열 방지)
    CONSTRAINT ledger_adj_reason CHECK (kind <> 'ADJ' OR length(btrim(reason)) > 0)
);

-- ── 등급 스냅샷(파생·이력) ───────────────────────────────────────────
-- grade_recalc(주 1회)가 append. 원장을 읽어 산출하는 파생값(01 §1).
-- 승급은 AS와 TI를 모두(AND) 만족해야 함(R3). 최신 행은 V8 뷰.
CREATE TABLE user_grades (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users(id),
    grade        grade_level NOT NULL,
    trust_index  NUMERIC(4,3) NOT NULL,   -- TI, 초기 0.4 (01 §5.2)
    active_score NUMERIC(12,4) NOT NULL,  -- AS (01 §5.3)
    judged_count INTEGER     NOT NULL,
    computed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_grade_snapshot_unique UNIQUE (user_id, computed_at)
);

-- ── "이거 더 뜰까요" 투표 ────────────────────────────────────────────
-- 판정과 물리적으로 분리한다 — 투표는 유행 여부 판정에 절대 입력되지 않는다(R1).
CREATE TABLE votes (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID        NOT NULL REFERENCES users(id),
    trend_item_id UUID        NOT NULL REFERENCES trend_items(id),
    will_trend    BOOLEAN     NOT NULL,   -- true=뜬다 / false=안 뜬다
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vote_one_per_user UNIQUE (user_id, trend_item_id)  -- 토글 가능(앱 '투표함')
);
