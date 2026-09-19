package kr.trendstage;

import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextSmokeTest extends AbstractIntegrationTest {

    @Test
    void contextLoadsAndFlywayAppliedAllMigrations() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(failed).isZero();
        assertThat(applied).isGreaterThanOrEqualTo(26);
    }
}
