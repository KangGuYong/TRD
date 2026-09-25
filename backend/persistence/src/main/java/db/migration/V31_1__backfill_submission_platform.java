package db.migration;

import kr.trendstage.domain.signal.PlatformResolver;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 기존 제보의 platform을 근거 링크로 채운다(SP4 §7). SQL로 따로 짜지 않는 이유 — 판별 규칙이
 * SQL과 Java 두 벌이 되면 어긋난다. 운영 경로(Submission 생성자)와 같은 PlatformResolver를 쓴다.
 */
public class V31_1__backfill_submission_platform extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        backfill(context.getConnection());
    }

    /** 테스트가 직접 부를 수 있게 분리. 커넥션을 닫거나 커밋하지 않는다(호출 측 트랜잭션). */
    public static void backfill(Connection connection) throws SQLException {
        try (Statement select = connection.createStatement();
             ResultSet rs = select.executeQuery("SELECT id, evidence_url FROM submissions WHERE platform IS NULL");
             PreparedStatement update = connection.prepareStatement("UPDATE submissions SET platform = ? WHERE id = ?")) {
            while (rs.next()) {
                update.setString(1, PlatformResolver.resolve(rs.getString(2)).name());
                update.setObject(2, rs.getObject(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
