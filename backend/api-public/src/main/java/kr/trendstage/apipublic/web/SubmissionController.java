package kr.trendstage.apipublic.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.SubmissionOrigin;
import kr.trendstage.apipublic.service.SubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 제보 API. OpenAPI /v1/submissions, /v1/submissions/me 대응. 인증 필요(PublicSecurityConfig). */
@RestController
public class SubmissionController {

    private final SubmissionService service;

    public SubmissionController(SubmissionService service) { this.service = service; }

    @PostMapping("/v1/submissions")
    public ResponseEntity<SubmissionMineResponse> create(Authentication auth,
                                                          @Valid @RequestBody SubmissionCreateRequest req,
                                                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                                          HttpServletRequest request) {
        var response = service.create(userId(auth), req, new SubmissionOrigin(deviceId, request.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/v1/submissions/me")
    public List<SubmissionMineResponse> mine(Authentication auth) {
        return service.mine(userId(auth));
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
