-- V14 · 관리자 로그인 비밀번호 해시 (세션 기반 인증)
-- 2FA는 이번 단계에서 미구현(twofa_enabled 컬럼은 유지, 검사만 안 함).
ALTER TABLE admin_accounts ADD COLUMN password_hash VARCHAR(100) NOT NULL DEFAULT '';
ALTER TABLE admin_accounts ALTER COLUMN password_hash DROP DEFAULT;
