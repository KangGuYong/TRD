package kr.trendstage.apipublic.web;

import kr.trendstage.apipublic.auth.FirebaseNotConfiguredException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DuplicateSubmissionException.class)
    public ResponseEntity<Map<String, Object>> handle(DuplicateSubmissionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "trendItemId", e.getTrendItemId(),
                "dupeRank", e.getDupeRank(),
                "message", e.getMessage()));
    }

    @ExceptionHandler(SubmissionValidationException.class)
    public ResponseEntity<Map<String, Object>> handle(SubmissionValidationException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem(422, e.getMessage()));
    }

    @ExceptionHandler(TrendNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(TrendNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }

    @ExceptionHandler(EndorseConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(EndorseConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(FirebaseNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handle(FirebaseNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem(503, e.getMessage()));
    }

    @ExceptionHandler(PreferencesNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(PreferencesNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }

    @ExceptionHandler(ReportNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handle(ReportNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem(404, e.getMessage()));
    }

    @ExceptionHandler(ExplanationForbiddenException.class)
    public ResponseEntity<Map<String, Object>> handle(ExplanationForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem(403, e.getMessage()));
    }

    @ExceptionHandler(ExplanationConflictException.class)
    public ResponseEntity<Map<String, Object>> handle(ExplanationConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem(409, e.getMessage()));
    }

    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<Map<String, Object>> handle(QuotaExhaustedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem("quota-exhausted", 422, e.getMessage()));
    }

    @ExceptionHandler(ItemClosedException.class)
    public ResponseEntity<Map<String, Object>> handle(ItemClosedException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(problem("item-closed", 422, e.getMessage()));
    }

    private Map<String, Object> problem(int status, String detail) {
        return problem("about:blank", status, detail);
    }

    /** RFC 9457. type으로 앱이 오류 종류를 구분한다(quota-exhausted·item-closed). */
    private Map<String, Object> problem(String type, int status, String detail) {
        return Map.of("type", type, "status", status, "detail", detail == null ? "" : detail);
    }
}
