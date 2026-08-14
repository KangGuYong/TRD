-- V17 · ADM-600 파라미터 드래프트 낙관적 락
-- 두 관리자가 동시에 드래프트를 수정("적용")하는 경합을 @Version으로 방어(ParamStudioService).
ALTER TABLE parameter_drafts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
