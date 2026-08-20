-- V26 · 신고 콘텐츠 큐 (ADM-410, B1)
-- 신고 대상은 trend_items(유저가 볼 수 있는 유일한 단위). 처리 과정에서 관리자가 특정
-- submission을 지목함으로써 실질적으로 "제보자를 신고"하는 효과를 낸다(설계서 §아키텍처).
-- 4h 자동 임시비공개는 의도적으로 넣지 않는다 — 오신고로 정상 콘텐츠가 사람 개입 없이
-- 비공개되는 것을 막기 위함(설계서 §범위).

CREATE TYPE trend_visibility AS ENUM ('PUBLIC', 'TEMP_HIDDEN', 'PERMANENT_HIDDEN');
ALTER TABLE trend_items ADD COLUMN visibility trend_visibility NOT NULL DEFAULT 'PUBLIC';
COMMENT ON COLUMN trend_items.visibility IS '신고 처리 결과의 표시 계층. 판정/점수 파이프라인과 완전히 분리(R2) — 비공개돼도 채점은 그대로 진행.';

CREATE TYPE report_status   AS ENUM ('OPEN', 'EXPLAINING', 'DECIDED');
CREATE TYPE report_reason   AS ENUM ('DEFAMATION', 'BUSINESS_INTERFERENCE', 'OTHER');
CREATE TYPE report_decision AS ENUM ('RESTORE', 'HIDE_PERMANENT', 'EDIT_RESTORE');

CREATE TABLE reports (
    id                       UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    trend_item_id            UUID            NOT NULL REFERENCES trend_items(id),
    reporter_id              UUID            NOT NULL REFERENCES users(id),
    reason                   report_reason   NOT NULL,
    detail                   TEXT,
    status                   report_status   NOT NULL DEFAULT 'OPEN',
    submission_id            UUID            REFERENCES submissions(id),
    explanation_deadline     TIMESTAMPTZ,
    explanation_text         TEXT,
    explanation_submitted_at TIMESTAMPTZ,
    decision                 report_decision,
    decision_note            TEXT,
    decided_by               UUID            REFERENCES admin_accounts(id),
    decided_at               TIMESTAMPTZ,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now()
);
COMMENT ON COLUMN reports.reporter_id IS '신고자 비식별 원칙: 관리자 API 응답 DTO에는 이 필드를 절대 넣지 않는다(컨트롤러 레이어에서 강제).';
COMMENT ON COLUMN reports.submission_id IS '1차 처리(hide/request-explanation) 시 관리자가 지목. 그 전엔 NULL — 최초 제보자를 자동 추정하지 않는다.';

CREATE INDEX idx_reports_status ON reports(status);
CREATE INDEX idx_reports_trend_item_id ON reports(trend_item_id);
CREATE INDEX idx_reports_submission_id ON reports(submission_id);
