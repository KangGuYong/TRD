-- V27 · 관리자 로그인 보강 (SP0.5)
-- 1) 무차별 대입 방어: 5회 연속 실패 시 15분 자동 잠금(수동 해제 경로 없음 — 시간 경과로만 해제).
--    카운터·잠금 시각은 계정 단위. 성공 로그인 시 초기화.
ALTER TABLE admin_accounts
    ADD COLUMN failed_login_count INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until       TIMESTAMPTZ;

COMMENT ON COLUMN admin_accounts.failed_login_count IS '연속 로그인 실패 횟수. 성공 또는 잠금 시 0으로 초기화.';
COMMENT ON COLUMN admin_accounts.locked_until IS '이 시각 전까지 로그인 거부(423). NULL 또는 과거면 잠금 아님.';

-- 2) twofa_enabled 정렬: DB 기본값은 TRUE인데 JPA 엔티티 기본값이 false여서 콘솔로 만든 계정이
--    전부 false로 들어갔다. 2FA 도입 시 기존 계정이 면제 상태로 남지 않도록 일괄 TRUE.
--    의미는 "이 계정은 2FA 적용 대상"(2FA 자체는 미구현 — SP3). 현재 이 값을 읽는 코드는 없다.
UPDATE admin_accounts SET twofa_enabled = TRUE;
COMMENT ON COLUMN admin_accounts.twofa_enabled IS '이 계정이 2FA 적용 대상인지. 2FA 검사는 미구현(SP3).';
