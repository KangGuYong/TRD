-- V5 · 어뷰징 플래그 · 이의 제기 · 제재
-- 자동은 플래그까지, 결정은 사람(R4). 자동 강등 경로는 스키마에도 두지 않는다.

-- ── 어뷰징 플래그(탐지 기록만) ───────────────────────────────────────
-- abuse_scan 배치가 INSERT. 제재 아님. 처리(오탐/경고/상신)는 status로 관리(02 ADM-300).
CREATE TABLE abuse_flags (
    id          UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID           NOT NULL REFERENCES users(id),
    rule_code   VARCHAR(20)    NOT NULL,   -- 예: R-002 다중계정, R-005 상호추천링, R-007 미고지 이해관계 (02 ADM-300)
    severity    abuse_severity NOT NULL,
    detail      JSONB          NOT NULL DEFAULT '{}',  -- 연결 그래프·상호 endorse 비율 등 근거
    status      abuse_status   NOT NULL DEFAULT 'OPEN',
    detected_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    resolved_by UUID           REFERENCES users(id)
);

-- ── 이의 제기 ────────────────────────────────────────────────────────
-- 처리기한 5영업일(약관 명시값과 동일해야 함). 통보문에 산정근거 수치 포함(02 ADM-400).
CREATE TABLE appeals (
    id          UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID            NOT NULL REFERENCES users(id),
    target_type VARCHAR(30)     NOT NULL,   -- 'VERDICT' | 'SANCTION' | 'GRADE' ...
    target_id   UUID            NOT NULL,
    status      appeal_status   NOT NULL DEFAULT 'RECEIVED',
    decision    appeal_decision,            -- 인용(상쇄원장 발생)/부분인용/기각
    assignee    UUID            REFERENCES users(id),
    reason_text TEXT,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    -- RESOLVED면 반드시 결정이 있어야 함
    CONSTRAINT appeal_resolved_has_decision
        CHECK (status <> 'RESOLVED' OR decision IS NOT NULL)
);

-- ── 제재(상신 → 2인 승인 → 실행) ─────────────────────────────────────
-- OPERATOR는 상신까지, 확정은 ADMIN 2인 승인(02 §1.1, ADM-300). approval_id로 V6 연결.
CREATE TABLE sanctions (
    id             UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    target_user_id UUID            NOT NULL REFERENCES users(id),
    type           VARCHAR(40)     NOT NULL,  -- 'WARNING' | 'GRADE_RESET' | 'SUSPEND' ...
    reason         TEXT            NOT NULL,
    requested_by   UUID            NOT NULL REFERENCES users(id),
    status         sanction_status NOT NULL DEFAULT 'REQUESTED',
    approval_id    UUID,                        -- V6 approval_requests(2인 승인)
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),
    executed_at    TIMESTAMPTZ
);
