package kr.trendstage.apipublic.web;

/** 존재하지 않는 신고(404). */
public class ReportNotFoundException extends RuntimeException {
    public ReportNotFoundException(String message) { super(message); }
}
