-- V12 · 외부 지표 수집 제거 — 판정 근거를 제보 시계열로 전환(CLAUDE.md R1 개정)
-- X/네이버 데이터랩/인스타/디시 등 외부 소스를 더 이상 쓰지 않는다. 제보(submissions)
-- 자체의 distinct 제보자 수·source_platform이 판정 입력이다.

DROP INDEX IF EXISTS idx_metric_snap_item_time;
DROP TABLE IF EXISTS metric_snapshots;
DROP TYPE IF EXISTS metric_source;
