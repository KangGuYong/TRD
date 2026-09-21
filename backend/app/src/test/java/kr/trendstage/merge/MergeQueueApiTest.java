package kr.trendstage.merge;

import jakarta.servlet.http.Cookie;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §7: 헤더 필수(400), 결정 응답 본문, 409 type 매핑. */
class MergeQueueApiTest extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    private UUID adminId;
    private Cookie csrf;

    @BeforeEach
    void setUp() throws Exception {
        adminId = fx.admin();
        clock.set(T0.plus(Duration.ofDays(1)));
        csrf = mvc.perform(get("/admin/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
    }

    private UUID candidate(UUID[] itemsOut) {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        itemsOut[0] = newItem;
        itemsOut[1] = oldItem;
        return fx.mergeQueueEntry(newItem, oldItem, 0.80);
    }

    private ResultActions postMerge(UUID queueId, String key) throws Exception {
        AdminPrincipal principal = new AdminPrincipal(adminId, "tester", "테스터", AdminRole.ADMIN);
        MockHttpServletRequestBuilder req = post("/admin/merge-queue/{id}/merge", queueId)
                .with(authentication(new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))))
                .cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"테스트\"}");
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    @Test
    void decisionWithoutKeyIs400() throws Exception {
        postMerge(candidate(new UUID[2]), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("idempotency-key-required"));
    }

    @Test
    void decisionReturnsResultBody() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MERGED"))
                .andExpect(jsonPath("$.decision").value("MERGE"))
                .andExpect(jsonPath("$.survivorId").value(items[1].toString()))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    void judgingTargetIs409WithType() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);
        fx.setState(items[1], "JUDGING");

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-judging"));
    }

    @Test
    void staleCandidateIs409AndCleanedUp() throws Exception {
        UUID[] items = new UUID[2];
        UUID queueId = candidate(items);
        fx.merged(items[0], fx.item(T0));

        postMerge(queueId, "k-" + UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-target-merged"));
        org.assertj.core.api.Assertions.assertThat(fx.queueStatus(queueId)).isEqualTo("SKIPPED");
    }
}
