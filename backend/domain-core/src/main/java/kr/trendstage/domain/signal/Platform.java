package kr.trendstage.domain.signal;

import java.util.List;

/**
 * 판정에 쓰는 플랫폼 목록(SP4 S4). 제보의 근거 링크 도메인으로 서버가 판별한다 — 유저가 고르는 값이 아니다.
 * 목록을 바꾸면 V31_2의 CHECK 제약도 새 마이그레이션으로 함께 바꾼다.
 */
public enum Platform {
    DCINSIDE("디시", "dcinside.com"),
    THEQOO("더쿠", "theqoo.net"),
    FMKOREA("에펨코리아", "fmkorea.com"),
    INSTIZ("인스티즈", "instiz.net"),
    X("X", "x.com", "twitter.com"),
    INSTAGRAM("인스타", "instagram.com"),
    THREADS("스레드", "threads.net", "threads.com"),
    YOUTUBE("유튜브", "youtube.com", "youtu.be"),
    TIKTOK("틱톡", "tiktok.com"),
    NAVER("네이버", "naver.com"),
    ETC("기타");

    private final String label;
    private final List<String> domains;

    Platform(String label, String... domains) {
        this.label = label;
        this.domains = List.of(domains);
    }

    public String label() { return label; }

    public List<String> domains() { return domains; }

    /** 저장된 코드 → 표시 라벨. 코드가 아니면(SP4 이전 자유 텍스트) 그대로 돌려준다. */
    public static String labelOf(String code) {
        if (code == null) return null;
        for (Platform p : values()) {
            if (p.name().equals(code)) return p.label;
        }
        return code;
    }
}
