package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.approval.ApprovalConflictException;
import kr.trendstage.apiadmin.auth.AccountDisabledException;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.apiadmin.auth.DuplicateLoginIdException;
import kr.trendstage.apiadmin.auth.InvalidCredentialsException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    @ExceptionHandler(DuplicateLoginIdException.class)
    public ResponseEntity<Map<String, Object>> handle(DuplicateLoginIdException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(ApprovalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(DraftLockedException.class)
    public ResponseEntity<Map<String, Object>> handle(DraftLockedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(SelfModificationException.class)
    public ResponseEntity<Map<String, Object>> handle(SelfModificationException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, e.getMessage()));
    }

    @ExceptionHandler(AdminValidationException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(422, e.getMessage()));
    }

    @ExceptionHandler(BatchJobAlreadyRunningException.class)
    public ResponseEntity<Map<String, Object>> handle(BatchJobAlreadyRunningException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handle(ObjectOptimisticLockingFailureException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, "다른 관리자가 먼저 수정했습니다 — 새로고침 후 다시 시도하세요"));
    }

    private Map<String, Object> problem(int status, String detail) {
        return Map.of("type", "about:blank", "status", status, "detail", detail == null ? "" : detail);
    }
}
