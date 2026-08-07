package kr.trendstage.apipublic.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 앱 API(/v1/**) 보안 체인. 콘솔(/admin)과 별도 필터체인으로 분리(04 §2.2).
 * 무상태 + JWT가 최종 형태다. 현재 슬라이스에서는 트렌드 조회를 공개로 두고
 * JWT 리소스서버 설정은 인증 모듈 확정(O6) 후 연결한다.
 */
@Configuration
public class PublicSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain publicApi(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/v1/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 트렌드 조회는 공개 읽기. 제보/나 등 쓰기·개인 리소스는 인증 필요.
                .requestMatchers("/v1/trends/**").permitAll()
                .anyRequest().authenticated()
            );
        // TODO(O6 인증 확정): .oauth2ResourceServer(o -> o.jwt(...))
        return http.build();
    }
}
