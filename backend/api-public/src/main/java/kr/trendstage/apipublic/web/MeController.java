package kr.trendstage.apipublic.web;

import kr.trendstage.apipublic.service.MeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** OpenAPI /v1/me/grade, /v1/me/ledger 대응. 인증 필요(PublicSecurityConfig). */
@RestController
public class MeController {

    private final MeService service;

    public MeController(MeService service) { this.service = service; }

    @GetMapping("/v1/me/grade")
    public GradeStatusResponse grade(Authentication auth) {
        return service.grade(userId(auth));
    }

    @GetMapping("/v1/me/ledger")
    public LedgerListResponse ledger(Authentication auth) {
        return service.ledger(userId(auth));
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
