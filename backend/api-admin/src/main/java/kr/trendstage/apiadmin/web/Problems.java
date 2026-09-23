package kr.trendstage.apiadmin.web;

import java.util.Map;

/** 콘솔 API 오류 본문 {type, status, detail}. type은 콘솔이 분기할 때 쓰고, 분기가 필요 없으면 about:blank. */
public final class Problems {
    private Problems() {}

    public static Map<String, Object> of(int status, String type, String detail) {
        return Map.of("type", type, "status", status, "detail", detail == null ? "" : detail);
    }
}
