-- V30 · SP3 콘솔 통제·SLA (docs/superpowers/specs/2026-09-23-sp3-console-control-design.md)

-- 1) 계정 통제(K2·K6). 기존 계정은 승인권·활성 모두 생성 시각부터 — 규칙 도입 전 계정을 잠그지 않는다.
ALTER TABLE admin_accounts
    ADD COLUMN approver_since TIMESTAMPTZ,
    ADD COLUMN activated_at   TIMESTAMPTZ,
    ADD COLUMN last_enabled_at TIMESTAMPTZ;
UPDATE admin_accounts SET approver_since = created_at, activated_at = created_at;
ALTER TABLE admin_accounts ALTER COLUMN approver_since SET NOT NULL;
COMMENT ON COLUMN admin_accounts.approver_since IS '이 시각부터 승인권이 있다. 생성·ADMIN 승격 시 +7일(P8). 부트스트랩 계정은 생성 시각.';
COMMENT ON COLUMN admin_accounts.activated_at IS 'NULL = 계정 생성 승인 대기(로그인 불가). 승인되거나 부트스트랩 예외로 만들면 채워진다.';
COMMENT ON COLUMN admin_accounts.last_enabled_at IS '마지막 재활성화 시각. sla_watch 90일 미접속 판단은 last_login_at·activated_at·last_enabled_at·created_at 중 가장 늦은 시각 기준 — 재활성화 직후 다시 꺼지지 않게.';

-- 2) 신고 자동 임시 비공개 기록(K9) — 있으면 sla_watch가 다시 처리하지 않는다
ALTER TABLE reports ADD COLUMN auto_hidden_at TIMESTAMPTZ;
COMMENT ON COLUMN reports.auto_hidden_at IS 'sla_watch가 4시간 SLA 초과로 이 신고를 자동 처리한 시각. 신고 상태는 OPEN 그대로 — 결정은 사람이 한다(R4).';

-- 3) 승인된 ADJ의 승인자(관리자). 옛 approved_by는 users FK라 관리자를 가리킬 수 없는 잔재 — 쓰지 않는다
ALTER TABLE score_ledger
    ADD COLUMN approved_by_admin_id UUID REFERENCES admin_accounts(id),
    ADD CONSTRAINT ledger_approval_pair CHECK ((approval_id IS NULL) = (approved_by_admin_id IS NULL));
COMMENT ON COLUMN score_ledger.approved_by_admin_id IS '2인 승인을 거친 ADJ의 승인 관리자. approval_id와 함께만 존재.';
COMMENT ON COLUMN score_ledger.approved_by IS '사용하지 않음(users FK라 관리자를 가리킬 수 없음). 승인자는 approved_by_admin_id.';

-- 4) 승인 1명 체계(K1): 남은 1/2 요청은 처음부터 다시 승인받는다
UPDATE approval_requests SET status = 'PENDING', approver_1 = NULL WHERE status = 'PARTIAL';

-- 5) 항목당 대기 중인 재판정·VOID 요청 하나(K5)
CREATE UNIQUE INDEX approval_one_pending_verdict_change
    ON approval_requests (target_ref)
    WHERE status = 'PENDING' AND action_type IN ('VERDICT_REJUDGE', 'ITEM_VOID');
