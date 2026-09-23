package kr.trendstage.apiadmin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.web.Problems;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;

/**
 * 세션 재검증(SP3 K7). 역할·활성 여부가 로그인 시점 값으로 세션에 박히므로, 요청마다 계정을 PK로 다시 읽어
 * 비활성·승인 대기·역할 불일치면 세션을 폐기하고 401로 끊는다 — 비활성화·역할 변경이 즉시 효력을 갖게 한다.
 * @Component로 등록하지 않는다(서블릿 필터로 전역 등록되면 /v1 체인까지 탄다). AdminSecurityConfig가 체인에 넣는다.
 *
 * 로그인·로그아웃·CSRF 엔드포인트는 제외한다(스펙 §5.3) — 그렇지 않으면 비활성·역할 변경 계정이
 * 로그아웃(감사 기록 포함)도, 재로그인 시도(컨트롤러의 자격증명 검사)도 못 하고 session-revoked에 막힌다.
 */
public class AdminSessionRevalidationFilter extends OncePerRequestFilter {

    private static final Set<String> EXCLUDED_PATHS = Set.of("/admin/auth/login", "/admin/auth/logout", "/admin/auth/csrf");

    private final AdminAccountRepository accounts;
    private final ObjectMapper objectMapper;

    public AdminSessionRevalidationFilter(AdminAccountRepository accounts, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return EXCLUDED_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            Optional<AdminAccount> found = accounts.findById(principal.id());
            String problem = null;
            if (found.isEmpty() || !found.get().isActive()) {
                problem = "사용할 수 없는 계정입니다 — 다시 로그인하세요";
            } else if (found.get().getRole() != principal.role()) {
                problem = "권한이 바뀌었습니다 — 다시 로그인하세요";
            }
            if (problem != null) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(Problems.of(401, "session-revoked", problem)));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
