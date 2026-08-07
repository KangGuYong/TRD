-- V11 · order_rank 뷰 컬럼을 integer로
-- Postgres RANK()는 bigint를 반환해 뷰 컬럼이 int8이 된다. 엔티티/도메인은 int(선점 순위는 소수)이므로
-- integer로 캐스팅해 Hibernate 스키마 검증(ddl-auto=validate) 불일치를 없앤다.
-- CREATE OR REPLACE는 컬럼 타입 변경을 허용하지 않으므로 DROP 후 재생성한다(뷰 의존 대상 없음).
DROP VIEW IF EXISTS submission_order_rank;

CREATE VIEW submission_order_rank AS
SELECT
    id,
    trend_item_id,
    CAST(RANK() OVER (PARTITION BY trend_item_id ORDER BY created_at ASC) AS integer) AS order_rank
FROM submissions
WHERE result <> 'VOID';
