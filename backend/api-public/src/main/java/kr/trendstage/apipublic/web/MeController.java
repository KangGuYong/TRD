package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.MeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** OpenAPI /v1/me/grade, /v1/me/ledger, /v1/me/summary, /v1/me/preferences 대응. 인증 필요(PublicSecurityConfig). */
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

    @GetMapping("/v1/me/summary")
    public MeSummaryResponse summary(Authentication auth) {
        return service.summary(userId(auth));
    }

    @GetMapping("/v1/me/preferences")
    public PreferencesResponse getPreferences(Authentication auth) {
        return service.getPreferences(userId(auth));
    }

    @PutMapping("/v1/me/preferences")
    public PreferencesResponse putPreferences(Authentication auth, @Valid @RequestBody PreferencesRequest req) {
        return service.savePreferences(userId(auth), req);
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
