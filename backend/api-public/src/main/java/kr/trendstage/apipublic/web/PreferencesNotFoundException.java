package kr.trendstage.apipublic.web;

/** 아직 온보딩을 완료하지 않은 유저 — GET /v1/me/preferences가 404로 응답할 때 쓴다. */
public class PreferencesNotFoundException extends RuntimeException {
    public PreferencesNotFoundException() { super("저장된 설정이 없습니다"); }
}
