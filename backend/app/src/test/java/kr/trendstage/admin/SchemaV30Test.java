package kr.trendstage.admin;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V30 제약과 새 컬럼 매핑. */
class SchemaV30Test extends AbstractIntegrationTest {

    @Autowired AdminAccountRepository accounts;

    @Test
    void approverSinceIsRequired() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO admin_accounts (login_id, display_name, role, password_hash) "
                + "VALUES (?, 'x', 'ADMIN', 'x')", "n_" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ledgerApprovalAndApproverComeTogether() {
        UUID user = fx.user();
        UUID admin = fx.admin();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO score_ledger (user_id, kind, delta, reason, halflife_days, "
                + "decay_anchor_at, approved_by_admin_id) VALUES (?, 'ADJ', 1, '사유', 90, now(), ?)", user, admin))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void onePendingVerdictChangePerItem() {
        UUID item = fx.item(Instant.parse("2026-08-01T00:00:00Z"));
        UUID requester = fx.admin();
        insertApproval("VERDICT_REJUDGE", item, requester, "PENDING");
        assertThatThrownBy(() -> insertApproval("ITEM_VOID", item, requester, "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 끝난 요청은 막지 않는다
        insertApproval("ITEM_VOID", UUID.randomUUID(), requester, "PENDING");
        UUID other = fx.item(Instant.parse("2026-08-01T00:00:00Z"));
        insertApproval("VERDICT_REJUDGE", other, requester, "EXECUTED");
        insertApproval("VERDICT_REJUDGE", other, requester, "PENDING");
    }

    @Test
    void adminAccountMapsNewColumns() {
        UUID pending = fx.pendingAdmin("OPERATOR");
        AdminAccount a = accounts.findById(pending).orElseThrow();
        assertThat(a.getActivatedAt()).isNull();
        assertThat(a.isActive()).isFalse();
        assertThat(a.getApproverSince()).isNotNull();

        AdminAccount eligible = accounts.findById(fx.admin()).orElseThrow();
        assertThat(eligible.canApproveAt(Instant.now())).isTrue();
        AdminAccount grace = accounts.findById(fx.adminInGrace(Instant.now().plusSeconds(3600))).orElseThrow();
        assertThat(grace.canApproveAt(Instant.now())).isFalse();
    }

    @Test
    void reportMapsAutoHiddenAt() {
        UUID report = fx.report(fx.item(Instant.parse("2026-08-01T00:00:00Z")), "OPEN", Instant.parse("2026-08-01T00:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT auto_hidden_at FROM reports WHERE id = ?", Instant.class, report)).isNull();
    }

    private void insertApproval(String type, UUID target, UUID requester, String status) {
        jdbc.update("INSERT INTO approval_requests (action_type, target_ref, requested_by, status) "
                + "VALUES (?, ?, ?, ?::approval_status)", type, target, requester, status);
    }
}
