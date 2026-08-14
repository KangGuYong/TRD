package kr.trendstage.merge;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * trend_items.embedding(pgvector)은 JPA로 매핑하지 않는다(TrendItem 엔티티 주석 참조) — 여기서
 * 네이티브 SQL로만 다룬다. 코사인 거리 연산자 {@code <=>} 사용, 유사도 = 1 - 거리.
 */
@Component
public class TrendEmbeddingDao {

    /** MERGED된 항목은 후보에서 제외 — 이미 tombstone된 클러스터와는 비교할 필요 없음. */
    private static final String ACTIVE_STATES_SQL = "state <> 'MERGED'";

    private final JdbcTemplate jdbc;

    public TrendEmbeddingDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void updateEmbedding(UUID trendItemId, float[] vec) {
        jdbc.update("UPDATE trend_items SET embedding = CAST(? AS vector) WHERE id = ?",
                toVectorLiteral(vec), trendItemId);
    }

    public record SimilarMatch(UUID trendItemId, double similarity) {}

    /** 가장 유사한 다른 활성 항목 1건. 임베딩이 아직 없는 항목은 후보에서 제외. */
    public Optional<SimilarMatch> findMostSimilar(UUID excludeId, float[] vec) {
        String sql = """
                SELECT id, 1 - (embedding <=> CAST(? AS vector)) AS similarity
                FROM trend_items
                WHERE id <> ? AND embedding IS NOT NULL AND %s
                ORDER BY embedding <=> CAST(? AS vector)
                LIMIT 1
                """.formatted(ACTIVE_STATES_SQL);
        String literal = toVectorLiteral(vec);
        List<SimilarMatch> rows = jdbc.query(sql,
                (rs, i) -> new SimilarMatch((UUID) rs.getObject("id"), rs.getDouble("similarity")),
                literal, excludeId, literal);
        return rows.stream().findFirst();
    }

    private static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 8);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
