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

    @ExceptionHandler(FirebaseNotConfiguredException.class)
    public ResponseEntity<Map<String, Object>> handle(FirebaseNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem(503, e.getMessage()));
    }

    private Map<String, Object> problem(int status, String detail) {
        return Map.of("type", "about:blank", "status", status, "detail", detail == null ? "" : detail);
    }
}
