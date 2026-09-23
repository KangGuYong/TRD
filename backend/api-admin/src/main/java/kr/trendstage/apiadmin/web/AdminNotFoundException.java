package kr.trendstage.apiadmin.web;

/** 404 — 조회 대상이 없음. */
public class AdminNotFoundException extends RuntimeException {
    public AdminNotFoundException(String message) { super(message); }
}
