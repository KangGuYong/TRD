-- V25 · 등급 실시간 계산용 인덱스
-- MeService.grade()/summary()는 user_grades 스냅샷(주 1회 갱신, 최대 1주 묵을 수 있음) 대신
-- 매 요청마다 countByUserIdAndResult(userId, HIT/MISS)로 실시간 재계산한다(MeService.java 주석 참고).
-- 기존 idx_submissions_user(user_id, created_at)는 result가 포함돼 있지 않아 이 조회 패턴에
-- 맞지 않는다 — 등급 조회는 자주 호출되는 경로이므로 전용 인덱스를 추가한다.
CREATE INDEX idx_submissions_user_result ON submissions (user_id, result);
