package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAuthTest extends FunctionalTestBase {

    static Stream<Arguments> protectedEndpoints() {
        String id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", "/v1/me/grade"),
                Arguments.of("GET", "/v1/me/ledger"),
                Arguments.of("GET", "/v1/me/summary"),
                Arguments.of("GET", "/v1/me/preferences"),
                Arguments.of("PUT", "/v1/me/preferences"),
                Arguments.of("GET", "/v1/me/reads"),
                Arguments.of("POST", "/v1/me/reads"),
                Arguments.of("GET", "/v1/me/watch"),
                Arguments.of("POST", "/v1/me/watch"),
                Arguments.of("DELETE", "/v1/me/watch/anything"),
                Arguments.of("POST", "/v1/reports"),
                Arguments.of("GET", "/v1/reports/me"),
                Arguments.of("GET", "/v1/reports/received"),
                Arguments.of("POST", "/v1/reports/" + id + "/explanation"),
                Arguments.of("POST", "/v1/submissions"),
                Arguments.of("GET", "/v1/submissions/me"),
                Arguments.of("POST", "/v1/trends/" + id + "/vote"),
                Arguments.of("POST", "/v1/trends/" + id + "/endorse"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("protectedEndpoints")
    @DisplayName("USR-AUTH-01 보호 엔드포인트 18개는 인증 없이 401, 바디 없음")
    void protectedEndpointsRequireAuth(String method, String url) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("USR-AUTH-02 트렌드 목록·상세는 인증 없이 200")
    void trendReadsArePublic() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("auth"), 30));

        mvc.perform(get("/v1/trends")).andExpect(status().isOk());
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("USR-AUTH-03 무효 Bearer 토큰(Firebase 미설정)은 500이 아니라 401")
    void invalidTokenIs401() throws Exception {
        mvc.perform(get("/v1/me/grade").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USR-AUTH-04 /v1 POST는 CSRF 토큰 없이 처리된다(STATELESS)")
    void publicChainHasNoCsrf() throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), submissionBody(uniq("csrf"), 30, "X", "MEME", "설명"))
                .andExpect(status().isCreated());
    }
}
