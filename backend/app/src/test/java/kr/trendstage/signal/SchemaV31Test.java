package kr.trendstage.signal;

import db.migration.V31_1__backfill_submission_platform;
import kr.trendstage.domain.signal.Platform;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V31: 플랫폼 코드·해시 컬럼·백테스트 데이터셋(SP4 §7). */
class SchemaV31Test extends AbstractIntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    private UUID anySubmission() {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        return fx.submission(fx.user(), fx.item(t), 30, t.plusSeconds(60));
    }

    @Test
    void platformCheckAcceptsEveryCodeAndRejectsOthers() {
        UUID sub = anySubmission();
        for (Platform p : Platform.values()) {
            jdbc.update("UPDATE submissions SET platform = ? WHERE id = ?", p.name(), sub);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET platform = 'MYSPACE' WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET platform = NULL WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hashColumnsAcceptOnlyLowercaseHex64() {
        UUID sub = anySubmission();
        jdbc.update("UPDATE submissions SET device_hash = ?, ip_hash = ? WHERE id = ?", "a".repeat(64), "0".repeat(64), sub);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET device_hash = 'raw-device-id' WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET ip_hash = ? WHERE id = ?", "A".repeat(64), sub))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourcePlatformIsOptional() {
        UUID sub = anySubmission();
        jdbc.update("UPDATE submissions SET source_platform = NULL WHERE id = ?", sub);
        assertThat(jdbc.queryForObject("SELECT source_platform FROM submissions WHERE id = ?", String.class, sub)).isNull();
    }

    @Test
    void backfillResolvesFromEvidenceUrl() {
        // 제약을 잠시 풀고 NULL 행을 만든 뒤 백필을 돌리고, 트랜잭션을 롤백해 스키마를 되돌린다(PG DDL은 트랜잭션 안).
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE submissions DROP CONSTRAINT submission_platform_known");
            jdbc.execute("ALTER TABLE submissions ALTER COLUMN platform DROP NOT NULL");
            UUID a = anySubmission(), b = anySubmission(), c = anySubmission();
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://gall.dcinside.com/board/1", a);
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://youtu.be/abc", b);
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://blog.example.com/p", c);
            try {
                V31_1__backfill_submission_platform.backfill(DataSourceUtils.getConnection(dataSource));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            assertThat(fx.platform(a)).isEqualTo("DCINSIDE");
            assertThat(fx.platform(b)).isEqualTo("YOUTUBE");
            assertThat(fx.platform(c)).isEqualTo("ETC");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'submission_platform_known'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void backtestDatasetsAreImmutable() {
        UUID id = jdbc.queryForObject("INSERT INTO backtest_datasets (name, sha256, case_count, payload, uploaded_by) "
                + "VALUES ('불변', ?, 1, '{}'::jsonb, ?) RETURNING id", UUID.class,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), fx.admin());
        assertThatThrownBy(() -> jdbc.update("UPDATE backtest_datasets SET name = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM backtest_datasets WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void entityDerivesPlatformFromEvidenceUrl() {
        Submission s = new Submission(UUID.randomUUID(), UUID.randomUUID(), "새싹", "새싹", (short) 30,
                "인스타", "https://youtu.be/abc", "설명", false, false);
        assertThat(s.getPlatform()).isEqualTo("YOUTUBE");
        assertThat(s.getSourcePlatform()).isEqualTo("인스타");
    }
}
