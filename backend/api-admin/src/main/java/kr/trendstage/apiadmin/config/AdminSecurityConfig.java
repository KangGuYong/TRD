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
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 콘솔 API(/admin/**) 보안 체인. 앱(/v1/**)과 별도 필터체인으로 분리(04 §2.2).
 * 세션 기반(단일 인스턴스, 인메모리 HttpSession). 2FA는 이번 단계 미구현.
 *
 * TODO: 관리자 콘솔 프론트엔드가 붙으면 CSRF 토큰 헤더 방식을 확정하고 csrf()를 다시 활성화할 것.
 * 지금은 아직 별도 SPA가 없어 api-public과 동일하게 비활성화 상태.
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

    @Bean
    @Order(2)
    public SecurityFilterChain adminApi(HttpSecurity http, SecurityContextRepository securityContextRepository)
            throws Exception {
        http
            .securityMatcher("/admin/**")
            .csrf(csrf -> csrf.disable())
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.POST, "/admin/auth/login").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
