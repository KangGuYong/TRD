package kr.trendstage.signal;

import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 제보 API가 기기·IP를 해시로만 남기고, 플랫폼은 링크로 정한다(SP4 §4·S12). */
class SubmissionOriginTest extends AbstractIntegrationTest {

    static final String DC = "https://gall.dcinside.com/board/view/?id=t&no=1";
    static final String X = "https://x.com/a/status/1";

    @Autowired SubmissionRepository submissions;

    private ResultActions submit(UUID user, String name, String platformOrNull, String url,
                                 String deviceId, String remoteAddr) throws Exception {
        String plat = platformOrNull == null ? "" : "\"platform\":\"" + platformOrNull + "\",";
        MockHttpServletRequestBuilder req = post("/v1/submissions")
                .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                .with(r -> { r.setRemoteAddr(remoteAddr); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"category\":\"MEME\"," + plat + "\"evidenceUrl\":\"" + url
                        + "\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"설명\"}");
        if (deviceId != null) req.header("X-Device-Id", deviceId);
        return mvc.perform(req);
    }

    private Map<String, Object> row(String name) {
        return jdbc.queryForMap("SELECT platform, source_platform, device_hash, ip_hash FROM submissions WHERE raw_input = ?", name);
    }

    private static String unique() {
        return "sig_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void storesOnlyHashes() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "device-abc-123", "203.0.113.7").andExpect(status().isCreated());
        Map<String, Object> r = row(name);
        assertThat((String) r.get("device_hash")).matches("^[0-9a-f]{64}$").doesNotContain("device-abc");
        assertThat((String) r.get("ip_hash")).matches("^[0-9a-f]{64}$");
        assertThat(r.get("platform")).isEqualTo("DCINSIDE");
    }

    @Test
    void sameDeviceSameHash() throws Exception {
        String n1 = unique(), n2 = unique(), n3 = unique();
        submit(fx.user(), n1, null, DC, "shared-device", "203.0.113.1").andExpect(status().isCreated());
        submit(fx.user(), n2, null, DC, "shared-device", "203.0.113.2").andExpect(status().isCreated());
        submit(fx.user(), n3, null, DC, "other-device", "203.0.113.1").andExpect(status().isCreated());
        assertThat(row(n1).get("device_hash")).isEqualTo(row(n2).get("device_hash"));
        assertThat(row(n1).get("device_hash")).isNotEqualTo(row(n3).get("device_hash"));
        assertThat(row(n1).get("ip_hash")).isEqualTo(row(n3).get("ip_hash"));
    }

    @Test
    void missingHeaderStillSubmits() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, X, null, "203.0.113.9").andExpect(status().isCreated());
        assertThat(row(name).get("device_hash")).isNull();
        assertThat(row(name).get("ip_hash")).isNotNull();
        assertThat(row(name).get("platform")).isEqualTo("X");
    }

    @Test
    void tooLongDeviceIdIs422AndWritesNothing() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "d".repeat(129), "203.0.113.9").andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE raw_input = ?", Integer.class, name)).isZero();
        submit(fx.user(), unique(), null, DC, "d".repeat(128), "203.0.113.9").andExpect(status().isCreated());
    }

    @Test
    void legacyClientStillSubmits() throws Exception {   // Review Focus 3
        String name = unique();
        submit(fx.user(), name, "인스타", DC, null, "203.0.113.9").andExpect(status().isCreated());
        Map<String, Object> r = row(name);
        assertThat(r.get("platform")).isEqualTo("DCINSIDE");       // 칩이 아니라 링크
        assertThat(r.get("source_platform")).isEqualTo("인스타");   // 보관용
        assertThat(r.get("device_hash")).isNull();
    }

    @Test
    void newClientOmitsPlatform() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "dev", "203.0.113.9").andExpect(status().isCreated());
        assertThat(row(name).get("source_platform")).isNull();
    }

    @Test
    void distinctPlatformsAreCodes() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, null, "203.0.113.9").andExpect(status().isCreated());
        submit(fx.user(), name, null, X, null, "203.0.113.9").andExpect(status().isCreated());   // 다른 유저 → 같은 항목 합류
        UUID item = jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE raw_input = ? LIMIT 1", UUID.class, name);
        assertThat(submissions.findDistinctPlatforms(item, SubmissionResult.VOID)).containsExactlyInAnyOrder("DCINSIDE", "X");
    }
}
