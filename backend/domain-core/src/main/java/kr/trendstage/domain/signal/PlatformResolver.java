package kr.trendstage.domain.signal;

import java.util.Locale;

/**
 * 근거 링크 → 플랫폼(SP4 §3). 순수 함수. 호스트가 도메인과 같거나 "." + 도메인으로 끝날 때만 일치한다.
 * java.net.URI는 쓰지 않는다 — 한글·공백이 인코딩되지 않은 채 붙여 넣은 링크를 URISyntaxException으로
 * 거부해 전부 ETC가 되기 때문이다. 호스트만 직접 잘라 낸다.
 */
public final class PlatformResolver {
    private PlatformResolver() {}

    public static Platform resolve(String url) {
        String host = hostOf(url);
        if (host == null) return Platform.ETC;
        for (Platform p : Platform.values()) {
            for (String d : p.domains()) {
                if (host.equals(d) || host.endsWith("." + d)) return p;
            }
        }
        return Platform.ETC;
    }

    /** http(s) 링크의 호스트(소문자, 사용자정보·포트·끝 점 제거). 아니면 null. */
    static String hostOf(String url) {
        if (url == null) return null;
        String s = url.trim();
        int sep = s.indexOf("://");
        if (sep <= 0) return null;
        String scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        String rest = s.substring(sep + 3);
        int end = rest.length();
        for (char c : new char[]{'/', '?', '#', '\\'}) {   // 브라우저는 \를 /로 본다 — @ 앞의 \로 호스트를 속이지 못하게
            int i = rest.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) authority = authority.substring(at + 1);
        int colon = authority.indexOf(':');
        if (colon >= 0) authority = authority.substring(0, colon);
        String host = authority.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.isEmpty() ? null : host;
    }
}
