package kr.trendstage.domain.signal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PlatformResolverTest {

    @ParameterizedTest
    @CsvSource({
            "https://gall.dcinside.com/board/view/?id=trend&no=1, DCINSIDE",
            "https://m.dcinside.com/board/trend/1,                 DCINSIDE",
            "https://dcinside.com,                                 DCINSIDE",
            "https://theqoo.net/hot/1,                             THEQOO",
            "https://www.fmkorea.com/1,                            FMKOREA",
            "https://www.instiz.net/pt/1,                          INSTIZ",
            "https://x.com/user/status/1,                          X",
            "https://twitter.com/user/status/1,                    X",
            "https://www.instagram.com/p/abc/,                     INSTAGRAM",
            "https://www.threads.net/@a/post/1,                    THREADS",
            "https://www.threads.com/@a,                           THREADS",
            "https://www.youtube.com/watch?v=1,                    YOUTUBE",
            "https://youtu.be/abc,                                 YOUTUBE",
            "https://www.tiktok.com/@a/video/1,                    TIKTOK",
            "https://m.blog.naver.com/a/1,                         NAVER",
            "https://cafe.naver.com/a/1,                           NAVER",
            "HTTPS://WWW.YOUTUBE.COM:443/watch?v=1,                YOUTUBE",
            "https://user:pw@www.instagram.com/p/1,                INSTAGRAM",
            "http://gall.dcinside.com./board,                      DCINSIDE",
            "https://evil-dcinside.com/x,                          ETC",
            "https://dcinside.com.evil.net/x,                      ETC",
            "https://bit.ly/abc,                                   ETC",
            "https://t.co/abc,                                     ETC",
            "https://blog.example.com/post,                        ETC",
            "ftp://dcinside.com/x,                                 ETC",
            "gall.dcinside.com/board,                              ETC",
    })
    void resolvesByHostSuffix(String url, Platform expected) {
        assertEquals(expected, PlatformResolver.resolve(url));
    }

    @Test
    void hangulQueryStillResolves() {   // Review Focus 1 — URI 파서라면 URISyntaxException
        assertEquals(Platform.DCINSIDE, PlatformResolver.resolve("https://gall.dcinside.com/board/view/?id=새싹 챌린지"));
        assertEquals(Platform.NAVER, PlatformResolver.resolve("https://blog.naver.com/a/새싹 챌린지"));
    }

    @Test
    void backslashBeforeAtDoesNotSpoofHost() {
        assertEquals(Platform.ETC, PlatformResolver.resolve("https://evil.com\\@dcinside.com/"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not a url", "https://", "   "})
    void garbageIsEtc(String url) {
        assertEquals(Platform.ETC, PlatformResolver.resolve(url));
    }

    @Test
    void labelsAndDomains() {
        assertEquals("디시", Platform.labelOf("DCINSIDE"));
        assertEquals("기타", Platform.labelOf("ETC"));
        assertEquals("디시", Platform.labelOf("디시"));        // SP4 이전 자유 텍스트는 그대로
        assertNull(Platform.labelOf(null));
        assertEquals(11, Platform.values().length);
        assertTrue(Platform.ETC.domains().isEmpty());
        assertTrue(Arrays.stream(Platform.values()).filter(p -> p != Platform.ETC).allMatch(p -> !p.domains().isEmpty()));
    }
}
