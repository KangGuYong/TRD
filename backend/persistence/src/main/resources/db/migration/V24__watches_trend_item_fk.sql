-- V24 · watches → trend_items 참조 무결성 추가
-- 기존엔 watches.normalized_key가 trend_items.normalized_key와 문자열로만 매칭되고 DB가 이를
-- 전혀 보장하지 않았다(둘이 어긋나면 조용히 매칭 실패 — 실제로 겪었던 "워치가 안 잡히는" 버그의
-- 근본 원인이 이 구조였다). normalized_key에 UNIQUE를 걸고 FK로 연결해 DB가 직접 방어하게 한다.
--
-- ON UPDATE CASCADE: 판정 전 항목은 정규화 키가 바뀔 수 있으므로(관리자 정정 등),
-- trend_items.normalized_key가 바뀌면 이를 참조하는 watches도 자동으로 따라가게 한다.

-- 이 제약 적용 전, 매칭되는 트렌드 항목이 없는 워치 행 정리(테스트 데이터 잔재).
DELETE FROM watches w
WHERE NOT EXISTS (SELECT 1 FROM trend_items t WHERE t.normalized_key = w.normalized_key);

-- 기존 일반 인덱스를 UNIQUE 제약으로 대체(FK가 참조하려면 유니크해야 함).
DROP INDEX IF EXISTS idx_trend_items_normkey;
ALTER TABLE trend_items ADD CONSTRAINT trend_items_normalized_key_key UNIQUE (normalized_key);

ALTER TABLE watches
    ADD CONSTRAINT watches_normalized_key_fkey
    FOREIGN KEY (normalized_key) REFERENCES trend_items(normalized_key)
    ON UPDATE CASCADE;
