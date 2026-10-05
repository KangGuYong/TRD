package kr.trendstage.apipublic.service;

/** 제보 요청의 출처 — 서비스가 해시로만 바꿔 저장한다(SP4 §4). 원문을 로그에 남기지 않는다. */
public record SubmissionOrigin(String deviceId, String remoteAddr) {
    public static final SubmissionOrigin NONE = new SubmissionOrigin(null, null);
}
