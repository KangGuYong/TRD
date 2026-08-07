-- V1 · 확장 모듈
-- 트렌드 레이더 스키마. 상위 근거: 01-system-design.md §2.1, 03-merge-clustering.md, 04-development-plan.md §5
-- 시간 값은 전부 UTC(timestamptz)로 저장한다. 표시(KST) 변환은 애플리케이션 계층에서만.

-- UUID 기본키 생성용 (gen_random_uuid)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 병합·클러스터링 임베딩 유사도(0.85/0.75)를 별도 벡터DB 없이 처리 (04 §2.1, §5.4)
CREATE EXTENSION IF NOT EXISTS vector;
