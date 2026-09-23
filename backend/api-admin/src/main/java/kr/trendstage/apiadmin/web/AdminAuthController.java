package kr.trendstage.apiadmin.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kr.trendstage.apiadmin.account.AdminAccountManagementService;
import kr.trendstage.apiadmin.auth.AdminAccountService;
import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.persistence.entity.AdminAccount;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminAuthController {

    private final AdminAccountService accountService;
    private final AuditLogService auditLogService;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenRepository csrfTokenRepository;
    private final AdminAccountManagementService management;

    public AdminAuthController(AdminAccountService accountService, AuditLogService auditLogService,
                                SecurityContextRepository securityContextRepository,
                                CsrfTokenRepository csrfTokenRepository,
                                AdminAccountManagementService management) {
        this.accountService = accountService;
        this.auditLogService = auditLogService;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenRepository = csrfTokenRepository;
        this.management = management;
    }

    public record LoginRequest(String loginId, String password) {}
    public record AdminSummary(String loginId, String displayName, String role) {}

    @PostMapping("/auth/login")
    public AdminSummary login(@RequestBody LoginRequest req, HttpServletRequest request, HttpServletResponse response) {
        AdminAccount account = accountService.authenticate(req.loginId(), req.password());

        // 세션 고정 방어: 컨트롤러가 직접 로그인하므로 Spring 인증 필터의 세션 ID·CSRF 토큰 교체가
        // 일어나지 않는다. 여기서 명시적으로 수행한다. 세션이 아직 없으면 saveContext가 새로 만든다.
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        CsrfToken rotated = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(rotated, request, response);

        AdminPrincipal principal = new AdminPrincipal(account.getId(), account.getLoginId(),
                account.getDisplayName(), account.getRole());
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        // Spring Security 6의 SecurityContextHolderFilter는 요청 처리 도중의 인증 변경을 자동 저장하지 않는다 —
        // 세션에 명시적으로 커밋해야 다음 요청에서 로그인 상태가 유지된다.
        securityContextRepository.saveContext(context, request, response);

        auditLogService.record(account.getId(), account.getRole(), "LOGIN", "ADMIN_ACCOUNT", account.getId(), Map.of());

        return new AdminSummary(account.getLoginId(), account.getDisplayName(), account.getRole().name());
    }

    @PostMapping("/auth/logout")
    public void logout(HttpServletRequest request) {
        AdminPrincipal principal = (AdminPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        auditLogService.record(principal.id(), principal.role(), "LOGOUT", "ADMIN_ACCOUNT", principal.id(), Map.of());
        request.getSession().invalidate();
        SecurityContextHolder.clearContext();
    }

    @GetMapping("/me")
    public AdminPrincipal me() {
        return (AdminPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /** SPA 부팅용 — 응답에 XSRF-TOKEN 쿠키를 싣는 것 외에 하는 일이 없다(CsrfCookieFilter가 발급). */
    @GetMapping("/auth/csrf")
    public void csrf() {
    }

    public record PasswordChangeRequest(String current, String next) {}

    @PostMapping("/me/password")
    public void changePassword(@RequestBody PasswordChangeRequest req) {
        AdminPrincipal p = (AdminPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        management.changeOwnPassword(p.id(), p.role(), req.current(), req.next());
    }
}
