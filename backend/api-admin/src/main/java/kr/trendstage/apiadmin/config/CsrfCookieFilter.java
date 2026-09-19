package kr.trendstage.apiadmin.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 지연 로딩되는 CSRF 토큰을 요청마다 로드해, 새로 만들어진 토큰이 응답 쿠키로 실리게 한다.
 * 이게 없으면 토큰을 한 번도 읽지 않은 응답에는 XSRF-TOKEN 쿠키가 발급되지 않는다.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
        if (csrfToken != null) {
            csrfToken.getToken();
        }
        chain.doFilter(request, response);
    }
}
