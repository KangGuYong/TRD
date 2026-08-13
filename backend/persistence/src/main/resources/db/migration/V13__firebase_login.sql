-- V13 · Firebase Authentication 연동 (구글 로그인 우선, 카카오는 추후 Custom Token 경로로 추가)
-- 리프레시 토큰 회전은 Firebase SDK가 클라이언트에서 대신 처리하므로 백엔드에 별도 저장소를 두지 않는다.

ALTER TABLE users ADD COLUMN firebase_uid VARCHAR(128) UNIQUE;
