package kr.trendstage.admin;

import jakarta.servlet.http.Cookie;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCsrfTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "csrf-pass-1";
    private static final String COOKIE = "XSRF-TOKEN";
    private static final String HEADER = "X-XSRF-TOKEN";

    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;

    private String loginId;

    @BeforeEach
    void createAccount() {
        loginId = "csrf-" + UUID.randomUUID();
        accounts.saveAndFlush(new AdminAccount(loginId, "CSRF", AdminRole.OPERATOR, passwordEncoder.encode(PASSWORD)));
    }

    private String body() {
        return "{\"loginId\":\"" + loginId + "\",\"password\":\"" + PASSWORD + "\"}";
    }

    private Cookie fetchCsrfCookie() throws Exception {
        MvcResult r = mvc.perform(get("/admin/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE))
                .andExpect(cookie().httpOnly(COOKIE, false))
                .andReturn();
        return r.getResponse().getCookie(COOKIE);
    }

    @Test
    void loginWithoutTokenIs403() throws Exception {
        mvc.perform(post("/admin/auth/login").contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
    }

    @Test
    void csrfEndpointIssuesReadableCookie() throws Exception {
        Cookie c = fetchCsrfCookie();
        assertThat(c.getValue()).isNotBlank();
    }

    @Test
    void loginWithTokenSucceeds_rotatesSessionAndToken() throws Exception {
        Cookie token = fetchCsrfCookie();
        MockHttpSession session = new MockHttpSession();
        String sessionIdBefore = session.getId();

        MvcResult r = mvc.perform(post("/admin/auth/login")
                        .session(session)
                        .cookie(token).header(HEADER, token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(COOKIE))
                .andReturn();

        MockHttpServletResponse res = r.getResponse();
        assertThat(res.getCookie(COOKIE).getValue()).isNotEqualTo(token.getValue());
        assertThat(r.getRequest().getSession(false).getId()).isNotEqualTo(sessionIdBefore);
    }

    @Test
    void authenticatedWriteRequiresToken() throws Exception {
        Cookie token = fetchCsrfCookie();
        MvcResult login = mvc.perform(post("/admin/auth/login")
                        .cookie(token).header(HEADER, token.getValue())
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        Cookie rotated = login.getResponse().getCookie(COOKIE);

        mvc.perform(post("/admin/auth/logout").session(session))
                .andExpect(status().isForbidden());

        mvc.perform(post("/admin/auth/logout").session(session)
                        .cookie(rotated).header(HEADER, rotated.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void publicApiChainIsUnaffected() throws Exception {
        // 앱 체인은 STATELESS Bearer — CSRF 대상이 아니므로 토큰 없이도 403이 아니라 인증 실패(401)여야 한다.
        mvc.perform(post("/v1/submissions").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
