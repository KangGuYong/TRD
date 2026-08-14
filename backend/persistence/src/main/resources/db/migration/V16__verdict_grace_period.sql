-- V16 · 판정 유예 연장 (ADM-200)
-- 관리자가 D+14 판정 시점을 최대 D+21까지 미룰 수 있다. NULL이면 기본 D+14 그대로.
ALTER TABLE trend_items ADD COLUMN judgment_deadline_override TIMESTAMPTZ;
