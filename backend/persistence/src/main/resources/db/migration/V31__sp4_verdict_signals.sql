-- SP4 판정 신호 재설계(스펙 2026-09-24 §7). 기존 행을 채우는 방식 — 개발 DB 초기화 불필요.

-- 플랫폼 코드(근거 링크로 판별, S4). V31_1이 기존 행을 채우고 V31_2가 NOT NULL·CHECK를 건다.
ALTER TABLE submissions ADD COLUMN platform VARCHAR(20);

-- 기기·IP 해시(HMAC, 원문 미보관, §4). 옛 제보·헤더 없는 요청은 NULL.
ALTER TABLE submissions ADD COLUMN device_hash VARCHAR(64)
    CONSTRAINT submission_device_hash_hex CHECK (device_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE submissions ADD COLUMN ip_hash VARCHAR(64)
    CONSTRAINT submission_ip_hash_hex CHECK (ip_hash ~ '^[0-9a-f]{64}$');

-- 유저가 고른 칩 값은 보관용(S12). 새 앱은 보내지 않는다.
ALTER TABLE submissions ALTER COLUMN source_platform DROP NOT NULL;

-- 상대 목표치의 활성 제보자 수(S2) — 판정마다 창(기본 28일) 범위 조회.
CREATE INDEX submission_active_window ON submissions (created_at) WHERE result <> 'VOID' AND NOT is_seed;

-- 백테스트 사례 파일(S11). 불변 — 승인자·감사자가 "어떤 데이터로 돌린 결과인가"를 확인한다.
CREATE TABLE backtest_datasets (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    sha256      VARCHAR(64)  NOT NULL CONSTRAINT backtest_dataset_sha_unique UNIQUE
                             CONSTRAINT backtest_dataset_sha_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    case_count  INT          NOT NULL CONSTRAINT backtest_dataset_case_count CHECK (case_count BETWEEN 1 AND 500),
    payload     JSONB        NOT NULL,
    uploaded_by UUID         NOT NULL REFERENCES admin_accounts (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TRIGGER backtest_datasets_immutable
    BEFORE UPDATE OR DELETE ON backtest_datasets
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- 드래프트의 마지막 백테스트 결과(승인 요청 조건, S9). 드래프트를 고치면 지운다.
ALTER TABLE parameter_drafts ADD COLUMN backtest_result JSONB;

COMMENT ON COLUMN submissions.platform IS '근거 링크 도메인으로 판별한 플랫폼 코드(SP4 S4). 판정 다양성 축·표시에 쓴다.';
COMMENT ON COLUMN submissions.source_platform IS '유저가 고른 최초 목격 플랫폼 칩(자유 텍스트). SP4부터 보관용 — 판정·표시에 쓰지 않는다.';
COMMENT ON COLUMN submissions.device_hash IS 'HMAC-SHA256(서버 비밀값, 기기 ID). 원문은 저장하지 않는다(SP4 §4).';
COMMENT ON COLUMN submissions.ip_hash IS 'HMAC-SHA256(서버 비밀값, IPv4 전체 또는 IPv6 /64). 원문은 저장하지 않는다(SP4 §4).';
COMMENT ON TABLE backtest_datasets IS 'ADM-600 백테스트 사례 파일. 불변(트리거). 정답 라벨은 평가용이지 판정 입력이 아니다(SP4 S10).';
COMMENT ON COLUMN parameter_drafts.backtest_result IS '드래프트의 마지막 백테스트 결과(데이터셋 해시 포함). 승인 요청 조건(SP4 S9).';
