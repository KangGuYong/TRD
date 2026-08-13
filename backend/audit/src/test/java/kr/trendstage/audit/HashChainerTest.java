package kr.trendstage.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HashChainerTest {

    private static final Instant T = Instant.parse("2026-08-13T00:00:00Z");

    @Test void 같은_입력이면_항상_같은_해시() {
        byte[] h1 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", "{}", T);
        byte[] h2 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", "{}", T);
        assertArrayEquals(h1, h2);
    }

    @Test void detail_키_순서가_달라도_정규화_후_동일_해시() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 2); a.put("a", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", 1); b.put("b", 2);

        String jsonA = HashChainer.canonicalDetailJson(a);
        String jsonB = HashChainer.canonicalDetailJson(b);
        assertEquals(jsonA, jsonB);

        byte[] h1 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", jsonA, T);
        byte[] h2 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", jsonB, T);
        assertArrayEquals(h1, h2);
    }

    @Test void prevHash가_다르면_다른_해시() {
        byte[] h1 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", "{}", T);
        byte[] h2 = HashChainer.computeHash(h1, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", "{}", T);
        assertFalse(java.util.Arrays.equals(h1, h2));
    }

    @Test void action이_다르면_다른_해시() {
        byte[] h1 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGIN", "ADMIN_ACCOUNT", "target", "{}", T);
        byte[] h2 = HashChainer.computeHash(null, "actor", "ADMIN", "LOGOUT", "ADMIN_ACCOUNT", "target", "{}", T);
        assertFalse(java.util.Arrays.equals(h1, h2));
    }
}
