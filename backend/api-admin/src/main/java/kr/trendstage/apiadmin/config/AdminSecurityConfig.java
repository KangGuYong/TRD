package kr.trendstage.apiadmin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

/**
 * 콘솔 API(/admin/**) 보안 체인. 앱(/v1/**)과 별도 필터체인으로 분리(04 §2.2).
 * 세션 기반(단일 인스턴스, 인메모리 HttpSession). 2FA는 미구현(SP3).
 *
 * CSRF: 쿠키-헤더 이중 제출. 서버가 XSRF-TOKEN 쿠키(JS 읽기 가능)를 발급하고, SPA는 쓰기 요청마다
 * 그 값을 X-XSRF-TOKEN 헤더로 되돌려 보낸다. 로그인 요청도 CSRF 검사를 받는다(login CSRF 방지) —
 * SPA는 부팅 시 GET /admin/auth/csrf로 쿠키를 먼저 받는다.
 */
@Configuration
@EnableMethodSecurity
public class AdminSecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 컨트롤러에서 로그인 성공 시 SecurityContext를 세션에 명시적으로 저장하기 위해 재사용. */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** 로그인 성공 시 컨트롤러가 토큰을 재발급해야 하므로 체인과 같은 인스턴스를 빈으로 공유. */
    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repo.setCookiePath("/");
        return repo;
    }

    @Bean
    @Order(2)
    public SecurityFilterChain adminApi(HttpSecurity http, SecurityContextRepository securityContextRepository,
                                         CookieCsrfTokenRepository csrfTokenRepository)
            throws Exception {
        http
            .securityMatcher("/admin/**")
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/admin/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/admin/auth/login").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
