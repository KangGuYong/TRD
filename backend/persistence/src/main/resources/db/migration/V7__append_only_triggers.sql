-- V7 · 불변성 강제 (R2 · 02 P2)
-- 원장·판정·감사 로그는 append-only. UPDATE/DELETE를 DB가 물리적으로 거부한다.
-- ORM setter 미노출만으로는 부족하다 — 직접 SQL·마이그레이션 실수까지 막아야 분쟁 방어가 성립.

CREATE OR REPLACE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'append-only 위반: % 테이블은 % 할 수 없습니다 (정정은 상쇄행 추가로만)',
        TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER score_ledger_immutable
    BEFORE UPDATE OR DELETE ON score_ledger
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER verdicts_immutable
    BEFORE UPDATE OR DELETE ON verdicts
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER admin_audit_log_immutable
    BEFORE UPDATE OR DELETE ON admin_audit_log
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- user_grades는 스냅샷 이력이므로 재계산 결과를 계속 append. 과거 스냅샷도 정정하지 않는다.
CREATE TRIGGER user_grades_immutable
    BEFORE UPDATE OR DELETE ON user_grades
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- ── updated_at 자동 갱신(가변 테이블만) ──────────────────────────────
CREATE OR REPLACE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trend_items_touch
    BEFORE UPDATE ON trend_items
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TRIGGER votes_touch
    BEFORE UPDATE ON votes
    FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
