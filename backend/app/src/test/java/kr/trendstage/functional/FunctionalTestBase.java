package kr.trendstage.functional;

import com.jayway.jsonpath.JsonPath;
import kr.trendstage.scheduler.GradeRecalcJob;
import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 기능 테스트(카탈로그 2026-10-05) 공통 헬퍼. 제보·조회는 HTTP로, 판정·등급은 배치 직접 호출로 한다.
 * DB를 모든 테스트가 공유하므로 단언은 자기가 만든 id에만 한다.
 */
public abstract class FunctionalTestBase extends AbstractIntegrationTest {

    protected static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-09-21(월) 10:00 KST — 주 경계(월 00:00 KST) 직후라 그 주 제보권이 깨끗하다. */
    protected static final Instant T0 = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, KST).toInstant();
    /** T0에 처음 제보된 항목의 기본 관측 마감(D+14) 직후. */
    protected static final Instant AFTER_DEADLINE = T0.plus(Duration.ofDays(14)).plusSeconds(1);
    /** 인증 없음. */
    protected static final RequestPostProcessor ANON = request -> request;

    @Autowired private VerdictRunner verdictRunner;
    @Autowired private GradeRecalcJob gradeRecalcJob;

    protected static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** platform은 라벨("디시"·"인스타" 등). SP4부터 서버가 근거 링크 도메인으로 플랫폼을 판별하므로 링크를 그에 맞춘다. */
    protected static String submissionBody(String name, int confidence, String platform, String category, String oneLine) {
        String evidenceUrl = switch (platform) {
            case "디시" -> "https://gall.dcinside.com/board/view/1";
            case "인스타" -> "https://www.instagram.com/p/1";
            default -> "https://example.com/e";   // 기타(ETC)
        };
        return """
                {"name":"%s","category":"%s","platform":"%s","evidenceUrl":"%s",\
                "confidence":%d,"disclosure":false,"oneLine":"%s"}""".formatted(name, category, platform, evidenceUrl, confidence, oneLine);
    }

    protected ResultActions postSubmission(UUID user, String body) throws Exception {
        return mvc.perform(post("/v1/submissions").with(asUser(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** HTTP 제보 → 201을 기대하고 응답 JSON을 돌려준다(플랫폼 "X", 카테고리 MEME). */
    protected String submit(UUID user, String name, int confidence) throws Exception {
        return submit(user, name, confidence, "X");
    }

    protected String submit(UUID user, String name, int confidence, String platform) throws Exception {
        return body(postSubmission(user, submissionBody(name, confidence, platform, "MEME", "한 줄 설명"))
                .andExpect(status().isCreated()));
    }

    protected static <T> T read(String json, String path) {
        return JsonPath.read(json, path);
    }

    protected static UUID uuid(String json, String path) {
        return UUID.fromString(read(json, path));
    }

    /** 제보 응답 JSON → 그 제보가 속한 항목 id. */
    protected UUID itemOf(String submissionJson) {
        return fx.itemOf(uuid(submissionJson, "$.id"));
    }

    /** 응답 본문. charset 없는 application/json을 MockMvc는 ISO-8859-1로 읽으므로 UTF-8을 명시한다. */
    protected String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected String getOk(String url, RequestPostProcessor who, Object... vars) throws Exception {
        return body(mvc.perform(get(url, vars).with(who)).andExpect(status().isOk()));
    }

    protected ResultActions postJson(String url, RequestPostProcessor who, String json, Object... vars) throws Exception {
        return mvc.perform(post(url, vars).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions putJson(String url, RequestPostProcessor who, String json, Object... vars) throws Exception {
        return mvc.perform(put(url, vars).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** 관측 마감 지난 항목을 판정한다. DB 전체가 대상이다 — 단언은 자기 id에만. */
    protected void runVerdicts() {
        releaseBatchLock("verdict_runner");
        verdictRunner.run();
    }

    /** 공식 등급 스냅샷을 모든 유저에 대해 새로 쓴다. */
    protected void runGradeRecalc() {
        releaseBatchLock("grade_recalc");
        gradeRecalcJob.run();
    }

    /** 같은 순간에 n번 실행하고 각 HTTP 상태를 돌려준다. MockMvc에서 처리되지 않은 예외(throw)는 500으로 센다. */
    protected List<Integer> concurrently(int n, Callable<Integer> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception e) {
                        return 500;
                    }
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : futures) statuses.add(f.get(30, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    /** JSON 숫자 배열 → 소수 넷째 자리로 반올림한 Double 목록(60 vs 60.0 같은 표기 차이 제거). */
    protected static List<Double> doubles(String json, String path) {
        List<Number> raw = read(json, path);
        return raw.stream().map(n -> Math.round(n.doubleValue() * 10000) / 10000.0).toList();
    }
}
