package kr.trendstage.apipublic.config;

import kr.trendstage.apipublic.auth.FirebaseAuthenticationFilter;
import kr.trendstage.apipublic.auth.FirebaseTokenVerifier;
import kr.trendstage.apipublic.auth.UserProvisioningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.http.HttpStatus;

/**
 * 앱 API(/v1/**) 보안 체인. 콘솔(/admin)과 별도 필터체인으로 분리(04 §2.2).
 * 로그인은 클라이언트가 Firebase SDK로 직접 처리(구글). 백엔드는 Firebase ID 토큰만 검증한다 —
 * 별도 로그인/리프레시 엔드포인트가 없다. 카카오는 추후 Custom Token 발급 경로로 추가 예정.
 */
@Configuration
public class PublicSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain publicApi(HttpSecurity http, FirebaseTokenVerifier verifier,
                                         UserProvisioningService provisioning) throws Exception {
        http
            .securityMatcher("/v1/**")
            // /v1/**는 STATELESS Bearer JWT — 브라우저가 자격증명을 자동 첨부하지 않으므로 CSRF 대상이 아니다.
            // (쿠키 세션을 쓰는 /admin/**만 CSRF를 켠다 — AdminSecurityConfig)
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new FirebaseAuthenticationFilter(verifier, provisioning),
                    UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                // 트렌드 "조회"만 공개. 투표/동의(POST)는 유저별 1회 제약이 있어 인증 필요 — /trends/** 전체를 열면 안 됨.
                .requestMatchers(HttpMethod.GET, "/v1/trends", "/v1/trends/*").permitAll()
                .anyRequest().authenticated()
            )
            // 미인증 요청은 401(Unauthorized) — OpenAPI 계약과 일치(기본값은 403).
            .exceptionHandling(e -> e.authenticationEntryPoint(
                    (AuthenticationEntryPoint) new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return http.build();
    }
}
