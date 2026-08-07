-- V2 · 열거형 타입
-- 도메인 enum은 네이티브 타입으로 고정(자기문서화). 값이 자주 바뀌는 파라미터성 집합은
-- enum이 아니라 parameter_drafts(JSONB)로 다룬다(04 §7.2).

CREATE TYPE user_status        AS ENUM ('ACTIVE', 'SUSPENDED', 'DEACTIVATED');

-- 제보 카테고리: 밈 / 상품 / 인물·채널 / 챌린지 / 슬랭 / 기타 (01 §3.1)
CREATE TYPE trend_category     AS ENUM ('MEME', 'PRODUCT', 'PERSON_CHANNEL', 'CHALLENGE', 'SLANG', 'ETC');

-- TrendItem 상태전이: DRAFT→PENDING→JUDGING→RESOLVED|VOID, 병합 패자는 MERGED(tombstone) (01 §2.2, 03 §4.2)
CREATE TYPE trend_state        AS ENUM ('DRAFT', 'PENDING', 'JUDGING', 'RESOLVED', 'VOID', 'MERGED');

CREATE TYPE submission_result  AS ENUM ('PENDING', 'HIT', 'MISS', 'VOID');

-- 수집 소스 (01 §4.1 S1~S5)
CREATE TYPE metric_source      AS ENUM ('X', 'DCINSIDE', 'NAVER_DATALAB', 'INSTAGRAM', 'DERIVED');

CREATE TYPE verdict_result     AS ENUM ('HIT', 'MISS', 'VOID');

-- 확산 규모 (01 §4.3). MISS/VOID면 NULL
CREATE TYPE reach_level        AS ENUM ('L1', 'L2', 'L3', 'L4');

-- 원장 구분. ADJ = 상쇄 원장(정정), 원본은 수정하지 않는다 (R2, 02 P2)
CREATE TYPE ledger_kind        AS ENUM ('HIT', 'MISS', 'VOID', 'ADJ');

CREATE TYPE grade_level        AS ENUM ('L0', 'L1', 'L2', 'L3', 'L4');

CREATE TYPE abuse_severity     AS ENUM ('LOW', 'MEDIUM', 'HIGH');
CREATE TYPE abuse_status       AS ENUM ('OPEN', 'DISMISSED', 'ESCALATED', 'ACTIONED');

CREATE TYPE appeal_status      AS ENUM ('RECEIVED', 'ASSIGNED', 'REVIEWING', 'RESOLVED');
CREATE TYPE appeal_decision    AS ENUM ('UPHELD', 'PARTIAL', 'REJECTED');

CREATE TYPE sanction_status    AS ENUM ('REQUESTED', 'APPROVED', 'EXECUTED', 'REJECTED');

-- 파라미터 스튜디오 상태 (02 ADM-600)
CREATE TYPE param_status       AS ENUM ('DRAFT', 'REVIEW', 'APPROVED', 'APPLIED', 'ROLLED_BACK');
CREATE TYPE apply_mode         AS ENUM ('SCHEDULED', 'RETROACTIVE');

-- 2인 승인(4-eyes) 상태머신 (04 §8)
CREATE TYPE approval_status    AS ENUM ('PENDING', 'PARTIAL', 'APPROVED', 'REJECTED', 'EXECUTED');

-- RBAC 역할 (02 §1)
CREATE TYPE admin_role         AS ENUM ('REVIEWER', 'OPERATOR', 'ADMIN', 'AUDITOR');
