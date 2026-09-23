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

/**
 * 세션 재검증(SP3 K7). 역할·활성 여부가 로그인 시점 값으로 세션에 박히므로, 요청마다 계정을 PK로 다시 읽어
 * 비활성·승인 대기·역할 불일치면 세션을 폐기하고 401로 끊는다 — 비활성화·역할 변경이 즉시 효력을 갖게 한다.
 * @Component로 등록하지 않는다(서블릿 필터로 전역 등록되면 /v1 체인까지 탄다). AdminSecurityConfig가 체인에 넣는다.
 */
public class AdminSessionRevalidationFilter extends OncePerRequestFilter {

    private final AdminAccountRepository accounts;
    private final ObjectMapper objectMapper;

    public AdminSessionRevalidationFilter(AdminAccountRepository accounts, ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal) {
            Optional<AdminAccount> account = accounts.findById(principal.id());
            String problem = null;
            if (account.isEmpty() || !account.get().isActive()) {
                problem = "사용할 수 없는 계정입니다 — 다시 로그인하세요";
            } else if (account.get().getRole() != principal.role()) {
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
