-- V6 · 관리자 계정 · 파라미터 스튜디오 · 2인 승인 · 감사 로그

-- ── 관리자 계정(RBAC) ────────────────────────────────────────────────
-- 앱 users와 분리. 2FA·30분 세션·권한 최소화(02 P6, ADM-800).
CREATE TABLE admin_accounts (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    login_id     VARCHAR(60) NOT NULL UNIQUE,
    display_name VARCHAR(60) NOT NULL,
    role         admin_role  NOT NULL,
    twofa_enabled BOOLEAN    NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    disabled_at  TIMESTAMPTZ,               -- 90일 미접속 자동 비활성/퇴사 회수
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 파라미터 스튜디오 드래프트 ───────────────────────────────────────
-- 즉시반영 금지: 드래프트 → 시뮬 → 2인 승인 → 예약(비소급 기본) (02 P5, ADM-600).
-- 가중치·임계값·반감기·승급요구치를 payload(JSONB)로. 엔진은 이걸 ParameterSet으로 주입받음(04 §4).
CREATE TABLE parameter_drafts (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    author_id    UUID         NOT NULL REFERENCES admin_accounts(id),
    status       param_status NOT NULL DEFAULT 'DRAFT',
    payload      JSONB        NOT NULL,     -- { weights:{S1..S5}, thresholds, halflife, grade_reqs, ... }
    sim_result   JSONB,                     -- 시뮬 없이는 승인요청 불가(02 ADM-600 안전장치 1). 없으면 REVIEW 진입 차단(앱 계층)
    apply_mode   apply_mode   NOT NULL DEFAULT 'SCHEDULED',  -- 기본 예약(비소급). RETROACTIVE는 2인+사유서
    apply_at     TIMESTAMPTZ,
    approval_id  UUID,                       -- 아래 approval_requests
    superseded_by UUID        REFERENCES parameter_drafts(id), -- 롤백/버전 이력(원클릭 롤백 근거)
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    applied_at   TIMESTAMPTZ
);

-- ── 2인 승인(4-eyes) 상태머신 ────────────────────────────────────────
-- 대상: 유저제재·등급수동조정·파라미터적용·상쇄원장 100점 초과(02 §1.1).
-- 요청자 ≠ 승인자를 CHECK로 강제. 두 번째 승인자도 첫 승인자와 달라야 함.
CREATE TABLE approval_requests (
    id            UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    action_type   VARCHAR(40)     NOT NULL,  -- 'SANCTION' | 'GRADE_ADJUST' | 'PARAM_APPLY' | 'LEDGER_ADJ_OVER100'
    target_ref    UUID            NOT NULL,   -- 대상 엔티티 id
    requested_by  UUID            NOT NULL REFERENCES admin_accounts(id),
    approver_1    UUID            REFERENCES admin_accounts(id),
    approver_2    UUID            REFERENCES admin_accounts(id),
    status        approval_status NOT NULL DEFAULT 'PENDING',
    payload       JSONB           NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved_at   TIMESTAMPTZ,
    CONSTRAINT approver1_not_requester CHECK (approver_1 IS NULL OR approver_1 <> requested_by),
    CONSTRAINT approver2_not_requester CHECK (approver_2 IS NULL OR approver_2 <> requested_by),
    CONSTRAINT approvers_distinct      CHECK (approver_1 IS NULL OR approver_2 IS NULL OR approver_1 <> approver_2)
);

-- 이제 앞서 참조만 걸어둔 approval_id FK를 실제로 연결
ALTER TABLE score_ledger      ADD CONSTRAINT fk_ledger_approval    FOREIGN KEY (approval_id) REFERENCES approval_requests(id);
ALTER TABLE sanctions         ADD CONSTRAINT fk_sanction_approval  FOREIGN KEY (approval_id) REFERENCES approval_requests(id);
ALTER TABLE parameter_drafts  ADD CONSTRAINT fk_param_approval     FOREIGN KEY (approval_id) REFERENCES approval_requests(id);

-- ── 감사 로그(append-only, 조회행위 포함) ────────────────────────────
-- Phase 0부터 필수. 나중에 붙이면 그 기간을 소급 증명 불가(02 ADM-700, 04 §8).
-- 위변조 방지: prev_hash → hash 해시체인. UPDATE/DELETE 차단은 V7.
CREATE TABLE admin_audit_log (
    id          BIGSERIAL   PRIMARY KEY,             -- 해시체인 순서 보장을 위한 단조증가 PK
    actor_id    UUID        REFERENCES admin_accounts(id),
    role        admin_role,
    action      VARCHAR(60) NOT NULL,                -- 'MERGE' | 'VOID' | 'LEDGER_ADJ' | 'PARAM_APPLY' | 'PII_VIEW' | 'LOGIN' ...
    target_type VARCHAR(40),
    target_id   UUID,
    detail      JSONB       NOT NULL DEFAULT '{}',
    prev_hash   BYTEA,                               -- 직전 행의 hash
    hash        BYTEA       NOT NULL,                -- H(prev_hash || 정규화된 행 내용)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
