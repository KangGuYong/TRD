package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.approval.ApprovalConflictException;
import kr.trendstage.apiadmin.auth.AccountDisabledException;
import kr.trendstage.apiadmin.auth.AccountLockedException;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.apiadmin.auth.DuplicateLoginIdException;
import kr.trendstage.apiadmin.auth.InvalidCredentialsException;
import kr.trendstage.apiadmin.auth.SelfModificationException;
import kr.trendstage.apiadmin.merge.IdempotencyKeyMismatchException;
import kr.trendstage.apiadmin.merge.IdempotencyKeyRequiredException;
import kr.trendstage.judge.JudgeConflictException;
import kr.trendstage.judge.JudgeRejectedException;
import kr.trendstage.merge.MergeConflictException;
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

    @ExceptionHandler(AccountLockedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccountLockedException e) {
        return ResponseEntity.status(HttpStatus.LOCKED).body(problem(423, e.getMessage()));
    }

    /** @PreAuthorize 거부(예: REVIEWER가 ADM-700 조회 시도) — 콘솔이 일관된 JSON으로 받도록 매핑. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(AccessDeniedException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, "권한이 없습니다"));
    }

    @ExceptionHandler(JudgeRejectedException.class)
    public ResponseEntity<Map<String, Object>> handle(JudgeRejectedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(422, e.getMessage()));
    }

    @ExceptionHandler(JudgeConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(JudgeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    /** 병합 거부 — type으로 판정 중(재시도)·판정 완료(영구)·이미 병합·이미 처리를 구분한다(SP2 K2). */
    @ExceptionHandler(MergeConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(MergeConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(409, e.type(), e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<Map<String, Object>> handle(IdempotencyKeyRequiredException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Problems.of(400, "idempotency-key-required", e.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyMismatchException.class)
    public ResponseEntity<Map<String, Object>> handle(IdempotencyKeyMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Problems.of(422, "idempotency-key-mismatch", e.getMessage()));
    }

    @ExceptionHandler(DuplicateLoginIdException.class)
    public ResponseEntity<Map<String, Object>> handle(DuplicateLoginIdException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(ApprovalConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(ApprovalConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(AdminConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Problems.of(409, e.type(), e.getMessage()));
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

    @ExceptionHandler(AdminNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(AdminNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
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
        return Problems.of(status, "about:blank", detail);
    }
}
