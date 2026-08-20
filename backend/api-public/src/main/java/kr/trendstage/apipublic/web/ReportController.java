package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.ReportService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 신고 API. OpenAPI /v1/reports 대응. 인증 필요(PublicSecurityConfig). */
@RestController
@RequestMapping("/v1/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<ReportMineResponse> create(Authentication auth, @Valid @RequestBody ReportCreateRequest req) {
        var response = service.create(userId(auth), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public List<ReportMineResponse> mine(Authentication auth) {
        return service.mine(userId(auth));
    }

    @GetMapping("/received")
    public List<ReportReceivedResponse> received(Authentication auth) {
        return service.received(userId(auth));
    }

    @PostMapping("/{id}/explanation")
    public ResponseEntity<Void> explanation(@PathVariable UUID id, Authentication auth,
                                             @Valid @RequestBody ExplanationRequest req) {
        service.submitExplanation(userId(auth), id, req.text());
        return ResponseEntity.ok().build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
