package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.WatchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** OpenAPI /v1/me/watch* 대응. 인증 필요(PublicSecurityConfig). */
@RestController
@RequestMapping("/v1/me/watch")
public class WatchController {

    private final WatchService service;

    public WatchController(WatchService service) { this.service = service; }

    @GetMapping
    public List<WatchItemResponse> list(Authentication auth) {
        return service.list(userId(auth));
    }

    @PostMapping
    public ResponseEntity<Void> add(Authentication auth, @Valid @RequestBody WatchRequest req) {
        service.add(userId(auth), req.keyword());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{keyword}")
    public ResponseEntity<Void> remove(Authentication auth, @PathVariable String keyword) {
        service.remove(userId(auth), keyword);
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
