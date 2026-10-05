package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserReadWatchTest extends FunctionalTestBase {

    private List<Map<String, Object>> watches(UUID u) throws Exception {
        return read(getOk("/v1/me/watch", asUser(u)), "$");
    }

    @Test
    @DisplayName("USR-RD-01 읽음 기록 2번 → 204 두 번, 목록에 id 1개(멱등)")
    void markReadIsIdempotent() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("read"), 30));
        UUID u = fx.user();
        String body = "{\"trendId\":\"" + item + "\"}";

        postJson("/v1/me/reads", asUser(u), body).andExpect(status().isNoContent());
        postJson("/v1/me/reads", asUser(u), body).andExpect(status().isNoContent());

        List<String> ids = read(getOk("/v1/me/reads", asUser(u)), "$");
        assertThat(ids).containsExactly(item.toString());
    }

    @Test
    @DisplayName("USR-RD-02 읽음: 없는 항목 404, trendId 누락 400")
    void markReadErrors() throws Exception {
        UUID u = fx.user();
        postJson("/v1/me/reads", asUser(u), "{\"trendId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        postJson("/v1/me/reads", asUser(u), "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-WCH-01 존재하는 항목 이름 워치 → 201, 목록에 {keyword, stage}")
    void watchExistingKeyword() throws Exception {
        clock.set(T0);
        String name = uniq("wch");
        submit(fx.user(), name, 30);
        UUID u = fx.user();

        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        assertThat(watches(u)).singleElement().satisfies(w -> {
            assertThat(w.get("keyword")).isEqualTo(name);
            assertThat(w.get("stage")).isEqualTo("SEED");
        });
    }

    @Test
    @DisplayName("USR-WCH-02 대소문자만 다른 키워드 재워치 → 201, 목록 1개, 처음 keyword 유지")
    void rewatchIsNoOp() throws Exception {
        clock.set(T0);
        String name = uniq("wchdup");
        submit(fx.user(), name, 30);
        UUID u = fx.user();

        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name.toUpperCase() + "\"}").andExpect(status().isCreated());

        assertThat(watches(u)).singleElement().satisfies(w -> assertThat(w.get("keyword")).isEqualTo(name));
    }

    @Test
    @DisplayName("USR-WCH-03 존재하지 않는 키워드 404, 빈 키워드 400")
    void watchErrors() throws Exception {
        UUID u = fx.user();
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + uniq("nothing") + "\"}").andExpect(status().isNotFound());
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-WCH-04 워치 해제는 있든 없든 204, 목록에서 빠진다")
    void unwatch() throws Exception {
        clock.set(T0);
        String name = uniq("unwch");
        submit(fx.user(), name, 30);
        UUID u = fx.user();
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        mvc.perform(delete("/v1/me/watch/{keyword}", name).with(asUser(u))).andExpect(status().isNoContent());
        mvc.perform(delete("/v1/me/watch/{keyword}", uniq("never")).with(asUser(u))).andExpect(status().isNoContent());

        assertThat(watches(u)).isEmpty();
    }
}
