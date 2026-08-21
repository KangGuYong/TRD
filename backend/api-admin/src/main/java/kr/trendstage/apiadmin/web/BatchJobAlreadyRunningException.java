package kr.trendstage.apiadmin.web;

public class BatchJobAlreadyRunningException extends RuntimeException {
    public BatchJobAlreadyRunningException(String message) {
        super(message);
    }
}
