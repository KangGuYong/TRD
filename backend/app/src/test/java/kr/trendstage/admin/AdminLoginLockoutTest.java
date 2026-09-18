package kr.trendstage.admin;

import jakarta.servlet.http.Cookie;
import kr.trendstage.apiadmin.auth.AdminAccountService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminLoginLockoutTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-1";

    @Autowired AdminAccountRepository accounts;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AdminAccountService accountService;

    private String loginId;
    private UUID accountId;

    private static final String CSRF_COOKIE = "XSRF-TOKEN";
    private static final String CSRF_HEADER = "X-XSRF-TOKEN";
    private Cookie csrfToken;

    @BeforeEach
    void createAccount() throws Exception {
        loginId = "lock-" + UUID.randomUUID();
        AdminAccount a = accounts.saveAndFlush(
                new AdminAccount(loginId, "잠금테스트", AdminRole.OPERATOR, passwordEncoder.encode(PASSWORD)));
        accountId = a.getId();
        // CSRF 토큰 미리 받기 — 로그인 요청마다 필요
        csrfToken = mvc.perform(get("/admin/auth/csrf"))
                .andReturn().getResponse().getCookie(CSRF_COOKIE);
    }

    private ResultActions login(String password) throws Exception {
        return mvc.perform(post("/admin/auth/login")
                .cookie(csrfToken).header(CSRF_HEADER, csrfToken.getValue())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }

    private int failedCount() {
        return jdbc.queryForObject("SELECT failed_login_count FROM admin_accounts WHERE id = ?",
                Integer.class, accountId);
    }

    private boolean lockedUntilSet() {
        return jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM admin_accounts WHERE id = ?",
                Boolean.class, accountId);
    }

    @Test
    void fourFailuresDoNotLock_andCounterPersists() throws Exception {
        for (int i = 0; i < 4; i++) login("wrong").andExpect(status().isUnauthorized());
        // 카운터가 예외와 함께 롤백되지 않았는지(noRollbackFor 회귀)
        assertThat(failedCount()).isEqualTo(4);
        assertThat(lockedUntilSet()).isFalse();
    }

    @Test
    void fifthFailureLocks_andIsAudited() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong").andExpect(status().isUnauthorized());

        assertThat(lockedUntilSet()).isTrue();
        Integer audits = jdbc.queryForObject(
                "SELECT count(*) FROM admin_audit_log WHERE action = 'LOGIN_LOCKED' AND target_id = ?",
                Integer.class, accountId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void lockedAccountRejectsEvenCorrectPasswordWith423() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong");

        login(PASSWORD)
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.status").value(423))
                .andExpect(jsonPath("$.detail").value("로그인 시도 횟수를 초과했습니다. 잠시 후 다시 시도하세요"));
    }

    @Test
    void lockExpiresAfter15Minutes_andSuccessResets() throws Exception {
        for (int i = 0; i < 5; i++) login("wrong");

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        login(PASSWORD).andExpect(status().isOk());
        assertThat(failedCount()).isZero();
        assertThat(lockedUntilSet()).isFalse();
    }

    @Test
    void successResetsCounter() throws Exception {
        for (int i = 0; i < 3; i++) login("wrong");
        login(PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login("wrong").andExpect(status().isUnauthorized());

        assertThat(lockedUntilSet()).isFalse();
    }

    @Test
    void concurrentFailuresAreNotLost() throws Exception {
        // 행 잠금이 없으면 동시에 들어온 실패가 카운터를 서로 덮어써 5회를 채우지 못한다(lost update).
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    accountService.authenticate(loginId, "wrong");
                } catch (RuntimeException expected) {
                    // InvalidCredentialsException — 실패가 기대 동작
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertThat(lockedUntilSet()).isTrue();
    }
}
