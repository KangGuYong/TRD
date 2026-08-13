package kr.trendstage.apipublic.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Authorization: Bearer <Firebase ID 토큰>을 검증해 SecurityContext에 내부 userId(UUID)를 심는다.
 * 토큰이 없거나 무효하면 그냥 통과시키고, 인증이 필요한 경로는 authorizeHttpRequests가 401 처리한다.
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private final FirebaseTokenVerifier verifier;
    private final UserProvisioningService provisioning;

    public FirebaseAuthenticationFilter(FirebaseTokenVerifier verifier, UserProvisioningService provisioning) {
        this.verifier = verifier;
        this.provisioning = provisioning;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                verifier.verify(header.substring(7)).ifPresent(token -> {
                    var userId = provisioning.resolve(token);
                    var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
            } catch (FirebaseNotConfiguredException e) {
                // Firebase 미설정 — 인증 없이 통과, 보호된 경로는 401로 막힘
            }
        }
        chain.doFilter(req, res);
    }
}
