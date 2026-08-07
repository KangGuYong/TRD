-- V3 · 핵심 도메인 테이블 (users, trend_items, submissions, endorsements, metric_snapshots)

-- ── 회원 ─────────────────────────────────────────────────────────────
-- 앱/콘솔 공용. 실명·연락처·디바이스지문 등 PII는 별도 테이블로 분리 예정(마스킹·접속기록 대상, 02 §4).
CREATE TABLE users (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    handle       VARCHAR(40) NOT NULL UNIQUE,
    status       user_status NOT NULL DEFAULT 'ACTIVE',
    joined_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    verified_at  TIMESTAMPTZ,                         -- 본인인증 완료 시각. 가입+7일 & 인증 전 제보 불가 (01 §3.3)
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── 트렌드 항목(병합된 클러스터) = 판정 단위 ──────────────────────────
CREATE TABLE trend_items (
    id             UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    canonical_name VARCHAR(120)   NOT NULL,           -- 최빈 표기(03 §3.4). 판정 통보 후 변경 차단은 앱 계층에서 강제
    normalized_key VARCHAR(160)   NOT NULL,           -- 대표 정규화 키(NFC 강제, 03 §2①)
    aliases        TEXT[]         NOT NULL DEFAULT '{}',
    category       trend_category NOT NULL,
    state          trend_state    NOT NULL DEFAULT 'DRAFT',
    first_seen_at  TIMESTAMPTZ    NOT NULL,           -- 최초 제보 시각. 변경 시 baseline 재계산 잡 트리거 필수(03 §3.2)
    -- 임베딩 차원은 임베딩 모델 선정(M2, 04 §13) 후 확정. 1024는 잠정값.
    embedding      vector(1024),
    version        INTEGER        NOT NULL DEFAULT 0, -- 낙관적 락(검수자 동시 병합 충돌 감지, 03 §6)
    merged_into    UUID           REFERENCES trend_items(id), -- tombstone: 병합 패자 → 승자 리다이렉트(03 §4.2). DELETE 금지
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    -- MERGED 상태면 반드시 승자를 가리키고, 그 외에는 가리키지 않는다
    CONSTRAINT trend_merged_consistency
        CHECK ((state = 'MERGED') = (merged_into IS NOT NULL))
);

-- ── 개별 제보 ────────────────────────────────────────────────────────
-- order_rank는 저장하지 않는다(파생값, V8 뷰). "최대값+1" 부여는 선점자를 뒤로 민다(03 §3.1).
CREATE TABLE submissions (
    id              UUID              PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID              NOT NULL REFERENCES users(id),
    trend_item_id   UUID              NOT NULL REFERENCES trend_items(id),
    raw_input       VARCHAR(120)      NOT NULL,
    normalized_key  VARCHAR(160)      NOT NULL,
    -- 확신도 = 베팅 포인트. 10/30/50만 허용 (01 §3.1, §5.1)
    confidence      SMALLINT          NOT NULL CHECK (confidence IN (10, 30, 50)),
    source_platform VARCHAR(60)       NOT NULL,       -- 최초 목격 플랫폼(디시 갤러리명 등 자유텍스트 포함)
    evidence_url    TEXT              NOT NULL,        -- 1건 이상 필수. 캡처만 있는 제보는 반려(앱 계층 검증)
    one_line        VARCHAR(200)      NOT NULL,
    disclosure      BOOLEAN           NOT NULL DEFAULT FALSE, -- 이해관계 고지. 미고지 적발 시 제재 근거(01 §11, 07 §7)
    result          submission_result NOT NULL DEFAULT 'PENDING',
    is_seed         BOOLEAN           NOT NULL DEFAULT FALSE, -- 시딩분: 점수 원장 미반영(01 §8, 02 ADM-500)
    created_at      TIMESTAMPTZ       NOT NULL DEFAULT now()  -- order_rank 산정 기준(변경 금지)
);

-- ── 동의(중복 제보 전환) ─────────────────────────────────────────────
-- 제보권 미소모. 적중 시 소액 가점(선점 4위 이하), 실패 무감점(01 §3.2).
-- 유저 단위 dedup: 같은 유저가 같은 클러스터에 중복 동의 불가(03 §4.4).
CREATE TABLE endorsements (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id  UUID        NOT NULL REFERENCES trend_items(id),
    user_id        UUID        NOT NULL REFERENCES users(id),
    src_submission UUID        REFERENCES submissions(id), -- 동의의 발단이 된 중복 제보(있으면)
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT endorse_user_dedup UNIQUE (trend_item_id, user_id)
);

-- ── 지표 시계열(원시값) ──────────────────────────────────────────────
-- 원시값만 저장한다. Δ(증가분)·기준선 배수 변환은 ScoreEngine에서만(R5, 04 §5.1).
-- 클러스터 병합 시 합산 금지 — 통합쿼리로 전 구간 재수집(03 §4.5).
CREATE TABLE metric_snapshots (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id UUID          NOT NULL REFERENCES trend_items(id),
    source        metric_source NOT NULL,
    metric        VARCHAR(60)   NOT NULL,   -- 예: 'high_engagement_posts', 'search_index', 'hashtag_delta'
    value         NUMERIC(18,4) NOT NULL,
    captured_at   TIMESTAMPTZ   NOT NULL,
    -- 시간당 배치 재실행 멱등성: 같은 (항목,소스,지표,시각)은 한 번만
    CONSTRAINT metric_snapshot_unique UNIQUE (trend_item_id, source, metric, captured_at)
);
