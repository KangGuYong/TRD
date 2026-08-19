package kr.trendstage.apipublic.web;

import jakarta.validation.Valid;
import kr.trendstage.apipublic.service.ReadService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * OpenAPI POST /v1/me/reads 대응. GET은 확장(OpenAPI 미기재) — 앱이 재시작/재로그인 시
 * 읽음 상태를 서버에서 다시 받아오는 용도(이전엔 인메모리라 새로고침마다 사라졌음).
 */
@RestController
@RequestMapping("/v1/me/reads")
public class ReadController {

    private final ReadService service;

    public ReadController(ReadService service) { this.service = service; }

    @GetMapping
    public List<UUID> list(Authentication auth) {
        return service.list(userId(auth));
    }

    @PostMapping
    public ResponseEntity<Void> markRead(Authentication auth, @Valid @RequestBody ReadRequest req) {
        service.markRead(userId(auth), req.trendId());
        return ResponseEntity.noContent().build();
    }

    private UUID userId(Authentication auth) {
        return (UUID) auth.getPrincipal();
    }
}
