package kr.trendstage.admin;

import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAccountDefaultsTest extends AbstractIntegrationTest {

    @Autowired AdminAccountRepository accounts;

    @Test
    void jpaCreatedAccountHasTwofaEnabledAndNoLock() {
        AdminAccount saved = accounts.saveAndFlush(
                new AdminAccount("defaults-" + UUID.randomUUID(), "기본값", AdminRole.OPERATOR, "x"));

        Boolean twofa = jdbc.queryForObject(
                "SELECT twofa_enabled FROM admin_accounts WHERE id = ?", Boolean.class, saved.getId());
        Integer failed = jdbc.queryForObject(
                "SELECT failed_login_count FROM admin_accounts WHERE id = ?", Integer.class, saved.getId());

        assertThat(twofa).isTrue();
        assertThat(failed).isZero();
        assertThat(saved.getLockedUntil()).isNull();
    }

    @Test
    void fifthFailureLocksAndResetsCounter() {
        AdminAccount a = new AdminAccount("lockunit-" + UUID.randomUUID(), "잠금", AdminRole.OPERATOR, "x");
        Instant now = Instant.parse("2026-09-18T00:00:00Z");

        for (int i = 1; i <= 4; i++) {
            assertThat(a.recordFailedLogin(now, 5, Duration.ofMinutes(15))).isFalse();
        }
        assertThat(a.recordFailedLogin(now, 5, Duration.ofMinutes(15))).isTrue();

        assertThat(a.isLocked(now)).isTrue();
        assertThat(a.isLocked(now.plus(Duration.ofMinutes(15)))).isFalse();
        assertThat(a.getFailedLoginCount()).isZero();

        a.recordLogin(now.plus(Duration.ofMinutes(16)));
        assertThat(a.getLockedUntil()).isNull();
    }
}
