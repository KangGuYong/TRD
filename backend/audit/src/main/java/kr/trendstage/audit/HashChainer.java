package kr.trendstage.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;

/**
 * 감사 로그 해시체인 계산 (순수 함수, DB/Spring 의존 없음).
 * hash = SHA-256(prevHash || 정규화된 행 내용). detail은 키 알파벳순 정렬 JSON으로 직렬화해
 * Map 순회 순서에 무관하게 같은 입력이면 항상 같은 해시가 나오도록 한다.
 */
public final class HashChainer {

    // SORT_PROPERTIES_ALPHABETICALLY는 POJO 프로퍼티만 정렬한다 — detail은 Map이라
    // ORDER_MAP_ENTRIES_BY_KEYS로 키를 정렬해야 순회 순서와 무관하게 같은 해시가 나온다.
    private static final ObjectMapper CANONICAL_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private HashChainer() {}

    public static String canonicalDetailJson(Map<String, ?> detail) {
        try {
            return CANONICAL_MAPPER.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (Exception e) {
            throw new IllegalArgumentException("감사로그 detail 직렬화 실패", e);
        }
    }

    public static byte[] computeHash(byte[] prevHash, String actorId, String role, String action,
                                      String targetType, String targetId, String canonicalDetailJson,
                                      Instant createdAt) {
        String joined = String.join(" ",
                prevHash == null ? "" : toHex(prevHash),
                nullToEmpty(actorId),
                nullToEmpty(role),
                nullToEmpty(action),
                nullToEmpty(targetType),
                nullToEmpty(targetId),
                nullToEmpty(canonicalDetailJson),
                createdAt.toString());
        return sha256(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
