package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AccountDisabledException;
import kr.trendstage.apiadmin.auth.InvalidCredentialsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class AdminApiExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handle(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem(401, e.getMessage()));
    }

    @ExceptionHandler(AccountDisabledException.class)
    public ResponseEntity<Map<String, Object>> handle(AccountDisabledException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, e.getMessage()));
    }

    /** @PreAuthorize 거부(예: REVIEWER가 ADM-700 조회 시도) — 콘솔이 일관된 JSON으로 받도록 매핑. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, "권한이 없습니다"));
    }

    private Map<String, Object> problem(int status, String detail) {
        return Map.of("type", "about:blank", "status", status, "detail", detail == null ? "" : detail);
    }
}
