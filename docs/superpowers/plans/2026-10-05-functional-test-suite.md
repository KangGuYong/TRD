# 백엔드 기능 테스트 스위트 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유저 API `/v1` 20개, 테스트가 없던 관리자 API 19개, "제보 → 병합 → 판정 → 원장 → 등급" 여정 9개를 MockMvc 통합 테스트로 고정하고, 설계와 다른 동작은 `BUG-n`으로 기록한다.

**Architecture:** 기존 `AbstractIntegrationTest`(Testcontainers pgvector 1개 공유 + `MutableClock` + MockMvc)에 크론 비활성화·임베딩 주소 고정·`asUser`를 더하고, `kr.trendstage.functional` 패키지에 `FunctionalTestBase`(HTTP 제보·배치 실행 헬퍼)와 영역별 테스트 클래스를 둔다. 운영 코드는 바꾸지 않는다.

**Tech Stack:** Java 17, Spring Boot 3.3 (MockMvc, spring-security-test), JUnit 5(+params), AssertJ, Hamcrest, Jayway JsonPath, Testcontainers. 새 의존성 없음.

**Spec:** `docs/superpowers/specs/2026-10-05-functional-test-suite-design.md` · TC 목록 `docs/superpowers/specs/2026-10-05-functional-test-catalog.md`

## Global Constraints

- 운영 코드(`src/main`) 수정 금지. 이 계획은 테스트·테스트 지원 코드·문서만 바꾼다.
- 새 Spring 컨텍스트를 만드는 `@MockBean`·`@SpyBean`·`@MockitoBean`·`@DynamicPropertySource`·클래스별 `@SpringBootTest(properties=…)` 금지.
- 모든 테스트 메서드에 `@DisplayName("<TC-ID> <설명>")`.
- 공유 DB: 단언은 테스트가 만든 id에만. 전역 개수 단언 금지. 이름·키는 `uniq(...)`로 랜덤.
- 배치 호출 전 항상 `releaseBatchLock(name)` — `FunctionalTestBase.runVerdicts()/runGradeRecalc()`가 해 준다. `shedlock` 행 DELETE 금지.
- 기대값이 실제 동작과 다르면 **기대값을 코드에 맞춰 고치지 않는다**: 테스트에 `@Disabled("BUG-n: <한 줄>")`, 카탈로그 상태 `BUG-n`, 태스크 보고에 기록. (예외: 기대값이 문서를 잘못 읽어 생긴 것이면 테스트를 고치고 카탈로그 비고에 근거를 적는다.)
- 시간은 `clock.set(...)`으로만 움직인다. `T0` = 2026-09-21(월) 10:00 KST.
- 커밋 메시지 끝: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. main 직접 푸시 금지(브랜치 + PR, 병합은 소유자).

## 실행 방법 (모든 태스크 공통)

이 PC에는 JDK가 없다. 아래 명령을 Git Bash에서 실행한다. `<FILTER>`만 바꾼다(예: `kr.trendstage.functional.user.UserSubmissionTest`, 전체 스위트는 `--tests` 인자째 지운다). 실패하면 실패 메시지를 콘솔에 출력한다.

```bash
MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "D:\\PJ\\TRD\\backend:/src:ro" -v trd-gradle-test-cache:/root/.gradle -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:17-jdk bash -c 'cp -r /src /workspace && cd /workspace && sed -i "s/\r$//" gradlew && ./gradlew --no-daemon :app:test --tests "<FILTER>"; rc=$?; grep -h -B2 -A20 "<failure" app/build/test-results/test/*.xml | head -300; exit $rc'
```

## BUG 번호 사전 배정

설계 §5 결정에 따라 실패가 예상되는 TC. 태스크에서 실제로 실패를 확인한 뒤 `@Disabled`를 단다(먼저 돌려 예상한 이유로 실패하는지 본다).

| BUG | TC | 예상 실패 |
|---|---|---|
| BUG-1 | USR-TRD-04 | 오늘의 5개가 비공개·병합된 항목을 계속 노출 |
| BUG-2 | USR-TRD-15 | 투표·인정 동시 첫 요청 500 |
| BUG-3 | USR-TRD-11 | 비공개 항목 투표·인정 200/201 (D1) |
| BUG-4 | USR-TRD-14 | 제보자 본인 인정 201 (D2) |
| BUG-5 | USR-RPT-08 | 소명 마감 후 제출 200 (D3) |
| BUG-6 | ADM-PRM-09 | REVIEW 중 simulate 200 (D4) |
| BUG-7 | ADM-PRM-04 | hitThreshold 범위 미검증 (D5) |
| BUG-8 | ADM-VRD-08 | MERGED·VOID 항목 유예 연장 200 (D8) |

예상 밖 실패는 원인을 조사해 테스트 실수면 고치고, 제품 동작이 근거와 다르면 BUG-9부터 새 번호를 붙인다.

## Review Focus

1. **공유 DB 잔여 데이터로 인한 순서 의존 실패** — 다른 클래스가 남긴 PENDING 항목·드래프트가 목록형 응답(오늘의 5개, 판정 임박 30개, 파라미터 드래프트 1개)을 흔든다. 기대: 기능 패키지 단독 실행과 전체 스위트 실행 모두 통과 → Task 12 Step 5에서 두 방식으로 모두 실행.
2. **macOS NFD 한글 입력** — 같은 이름이 다른 항목으로 갈라지면 선점·T가 쪼개진다. 기대: 같은 항목 → Task 2 `USR-SUB-11`.
3. **네트워크 재시도로 같은 제보 동시 2회** — 기대: 201 + 409, 500 없음, 1행 → Task 2 `USR-SUB-12`.
4. **이름 길이 경계(120자)** — 기대: 120자 201, 121자 400 → Task 2 `USR-SUB-13`·`USR-SUB-05`.
5. **벽시계 의존 값** — 오늘의 5개 날짜 경계·소명 제출 시각·병합 큐 `ago`는 `MutableClock`을 안 따른다. 기대: 테스트가 날짜에 따라 결과가 바뀌지 않음 → 해당 값은 단언하지 않는다(Task 3 `USR-TRD-03`은 같은 날 두 번 호출의 동일성만 본다).

---

### Task 1: 테스트 기반 — 크론 비활성화, 임베딩 주소 고정, `asUser`, `FunctionalTestBase`

**Files:**
- Modify: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/FunctionalTestBase.java`

**Interfaces:**
- Produces (모든 이후 태스크가 사용):
  - `AbstractIntegrationTest.asUser(UUID userId): RequestPostProcessor`
  - `Fixtures.handle(UUID userId): String`, `Fixtures.preferences(UUID userId, String... categories): void`, `Fixtures.verdict(UUID itemId, String result): void`(result = `"HIT"`(L1) | `"MISS"`)
  - `FunctionalTestBase` 상수 `KST`, `T0`, `AFTER_DEADLINE`(= T0 + 14일 + 1초), `ANON`
  - `FunctionalTestBase` 메서드: `uniq(String)`, `submissionBody(name, confidence, platform, category, oneLine)`, `postSubmission(UUID user, String body): ResultActions`, `submit(UUID user, String name, int confidence): String`, `submit(UUID user, String name, int confidence, String platform): String`, `read(String json, String path): T`, `uuid(String json, String path): UUID`, `itemOf(String submissionJson): UUID`, `body(ResultActions): String`, `getOk(String url, RequestPostProcessor who, Object... vars): String`, `postJson(String url, RequestPostProcessor who, String json, Object... vars): ResultActions`, `putJson(...)`, `runVerdicts()`, `runGradeRecalc()`, `concurrently(int n, Callable<Integer> call): List<Integer>`, `doubles(String json, String path): List<Double>`

- [ ] **Step 1: 기존 전체 스위트가 초록인지 기준선 확인**

Run: 실행 방법 명령에서 `--tests "<FILTER>"`를 지우고 실행.
Expected: `BUILD SUCCESSFUL`. 실패가 있으면 이 계획을 시작하지 말고 보고한다(기준선이 깨져 있으면 이후 결과를 해석할 수 없다).

- [ ] **Step 2: `AbstractIntegrationTest`에 크론 비활성화·임베딩 주소 추가**

`@SpringBootTest(properties = {...})` 배열을 아래로 바꾼다(기존 세 줄 유지 + 네 줄 추가):

```java
@SpringBootTest(properties = {
        // 로컬 전용 application-local.yml(있다면)이 켜는 SQL·바인딩 로그를 테스트에서는 끈다.
        "logging.level.org.hibernate.SQL=WARN",
        "logging.level.org.hibernate.orm.jdbc.bind=WARN",
        // 배치 크론은 전부 끈다 — 실제 시각이 크론에 걸리면 공유 MutableClock으로 DB 전체를 판정·병합·등급 재계산해
        // 다른 테스트를 흔든다(Spring '-'는 크론 비활성). 테스트는 배치를 직접 호출한다.
        "jobs.sla-watch.cron=-",
        "jobs.verdict-runner.cron=-",
        "jobs.cluster-merge.cron=-",
        "jobs.grade-recalc.cron=-",
        // 로컬에 임베딩 서비스(TEI, 기본 :6000)가 떠 있어도 붙지 않게 — cluster_merge 수동 실행 결과를 결정적으로.
        "embedding.service.url=http://127.0.0.1:1"
})
```

- [ ] **Step 3: `asUser` 추가**

`AbstractIntegrationTest`의 `asAdmin` 위에 추가:

```java
    /**
     * 앱 유저. Firebase 필터가 SecurityContext에 심는 것과 같은 principal(내부 userId UUID).
     * /v1 체인은 STATELESS·CSRF 없음이라 이것으로 충분하다 — 토큰 파싱만 건너뛰고 나머지 보안 체인은 그대로 탄다.
     */
    protected RequestPostProcessor asUser(UUID userId) {
        return authentication(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }
```

(필요한 import `UsernamePasswordAuthenticationToken`, `authentication`, `List`, `UUID`, `RequestPostProcessor`는 이미 있다.)

- [ ] **Step 4: `Fixtures`에 헬퍼 3개 추가**

`gradeSnapshot` 메서드 아래에 추가:

```java
    public String handle(UUID userId) {
        return jdbc.queryForObject("SELECT handle FROM users WHERE id = ?", String.class, userId);
    }

    /** 온보딩 완료(선호 카테고리, 알림 9시). */
    public void preferences(UUID userId, String... categories) {
        jdbc.update("INSERT INTO user_preferences (user_id, categories, notify_hour) VALUES (?, string_to_array(?, ','), 9)",
                userId, String.join(",", categories));
    }

    /** 현행 판정 행만 만든다(상태는 그대로). result = HIT(L1) | MISS. */
    public void verdict(UUID itemId, String result) {
        String values = "HIT".equals(result) ? "'HIT', 'L1', 0.2" : "'MISS', NULL, 0.1";
        jdbc.update("INSERT INTO verdicts (trend_item_id, result, reach_level, score_t, judged_at, evidence_json) "
                + "VALUES (?, " + values + ", now(), '{}'::jsonb)", itemId);
    }
```

- [ ] **Step 5: `FunctionalTestBase` 작성**

Create `backend/app/src/test/java/kr/trendstage/functional/FunctionalTestBase.java`:

```java
package kr.trendstage.functional;

import com.jayway.jsonpath.JsonPath;
import kr.trendstage.scheduler.GradeRecalcJob;
import kr.trendstage.scheduler.VerdictRunner;
import kr.trendstage.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

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

    protected static String submissionBody(String name, int confidence, String platform, String category, String oneLine) {
        return """
                {"name":"%s","category":"%s","platform":"%s","evidenceUrl":"https://example.com/e",\
                "confidence":%d,"disclosure":false,"oneLine":"%s"}""".formatted(name, category, platform, confidence, oneLine);
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

    protected String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString();
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
```

- [ ] **Step 6: 전체 스위트 재실행 — 기반 변경이 기존 테스트를 깨지 않는지**

Run: 실행 방법 명령(`--tests` 없이).
Expected: `BUILD SUCCESSFUL`(Step 1과 같은 결과). 깨지면 원인(크론 비활성화·임베딩 주소)을 보고하고 멈춘다.

- [ ] **Step 7: Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/functional/FunctionalTestBase.java && git commit -m "test: 기능 테스트 기반 — 배치 크론 전부 비활성화, 임베딩 주소 고정, asUser, FunctionalTestBase

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: USR-AUTH · USR-SUB — 인증 공통과 제보

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserAuthTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserSubmissionTest.java`

**Interfaces:**
- Consumes: Task 1의 `FunctionalTestBase` 전부, `fx.item/setState/key/itemOf/visibility/itemState`.

- [ ] **Step 1: `UserAuthTest` 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;

import java.util.UUID;
import java.util.stream.Stream;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAuthTest extends FunctionalTestBase {

    static Stream<Arguments> protectedEndpoints() {
        String id = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("GET", "/v1/me/grade"),
                Arguments.of("GET", "/v1/me/ledger"),
                Arguments.of("GET", "/v1/me/summary"),
                Arguments.of("GET", "/v1/me/preferences"),
                Arguments.of("PUT", "/v1/me/preferences"),
                Arguments.of("GET", "/v1/me/reads"),
                Arguments.of("POST", "/v1/me/reads"),
                Arguments.of("GET", "/v1/me/watch"),
                Arguments.of("POST", "/v1/me/watch"),
                Arguments.of("DELETE", "/v1/me/watch/anything"),
                Arguments.of("POST", "/v1/reports"),
                Arguments.of("GET", "/v1/reports/me"),
                Arguments.of("GET", "/v1/reports/received"),
                Arguments.of("POST", "/v1/reports/" + id + "/explanation"),
                Arguments.of("POST", "/v1/submissions"),
                Arguments.of("GET", "/v1/submissions/me"),
                Arguments.of("POST", "/v1/trends/" + id + "/vote"),
                Arguments.of("POST", "/v1/trends/" + id + "/endorse"));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("protectedEndpoints")
    @DisplayName("USR-AUTH-01 보호 엔드포인트 18개는 인증 없이 401, 바디 없음")
    void protectedEndpointsRequireAuth(String method, String url) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), url).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("USR-AUTH-02 트렌드 목록·상세는 인증 없이 200")
    void trendReadsArePublic() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("auth"), 30));

        mvc.perform(get("/v1/trends")).andExpect(status().isOk());
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("USR-AUTH-03 무효 Bearer 토큰(Firebase 미설정)은 500이 아니라 401")
    void invalidTokenIs401() throws Exception {
        mvc.perform(get("/v1/me/grade").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("USR-AUTH-04 /v1 POST는 CSRF 토큰 없이 처리된다(STATELESS)")
    void publicChainHasNoCsrf() throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), submissionBody(uniq("csrf"), 30, "X", "MEME", "설명"))
                .andExpect(status().isCreated());
    }
}
```

- [ ] **Step 2: `UserSubmissionTest` 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserSubmissionTest extends FunctionalTestBase {

    @Test
    @DisplayName("USR-SUB-01 새 이름 제보 → 201, PENDING·1위·D-14, 항목(PENDING·PUBLIC·first_seen=제보 시각) 생성")
    void newNameCreatesItem() throws Exception {
        clock.set(T0);
        String name = uniq("새트렌드");

        String res = submit(fx.user(), name, 30);

        assertThat((String) read(res, "$.status")).isEqualTo("PENDING");
        assertThat((Integer) read(res, "$.orderRank")).isEqualTo(1);
        assertThat(((Number) read(res, "$.judgeInDays")).longValue()).isEqualTo(14);
        assertThat((String) read(res, "$.word")).isEqualTo(name);
        UUID item = itemOf(res);
        assertThat(fx.itemState(item)).isEqualTo("PENDING");
        assertThat(fx.visibility(item)).isEqualTo("PUBLIC");
        assertThat(jdbc.queryForObject("SELECT first_seen_at FROM trend_items WHERE id = ?", Timestamp.class, item)
                .toInstant()).isEqualTo(T0);
        assertThat(jdbc.queryForObject("SELECT is_seed FROM submissions WHERE id = ?", Boolean.class,
                uuid(res, "$.id"))).isFalse();
    }

    @Test
    @DisplayName("USR-SUB-02 공백·대소문자만 다른 이름은 같은 항목에 2위로 합류")
    void normalizedNameJoinsExistingItem() throws Exception {
        clock.set(T0);
        String base = "foo bar " + UUID.randomUUID().toString().substring(0, 8);
        UUID item = itemOf(submit(fx.user(), base, 30));

        clock.set(T0.plusSeconds(60));
        String res = submit(fx.user(), "  " + base.toUpperCase().replace(" ", "   ") + " ", 30);

        assertThat(itemOf(res)).isEqualTo(item);
        assertThat((Integer) read(res, "$.orderRank")).isEqualTo(2);
    }

    @Test
    @DisplayName("USR-SUB-03 같은 유저의 같은 항목 재제보는 409 {trendItemId, dupeRank}(Problem 아님), 제보권 차감 없음")
    void duplicateIs409WithBody() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String name = uniq("dup");
        UUID item = itemOf(submit(u, name, 30));

        postSubmission(u, submissionBody(name, 30, "X", "MEME", "설명"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.trendItemId").value(item.toString()))
                .andExpect(jsonPath("$.dupeRank").value(1))
                .andExpect(jsonPath("$.type").doesNotExist());
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(1));
    }

    @Test
    @DisplayName("USR-SUB-04 확신도가 10/30/50이 아니면 422")
    void invalidConfidenceIs422() throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), submissionBody(uniq("conf"), 20, "X", "MEME", "설명"))
                .andExpect(status().isUnprocessableEntity());
    }

    static Stream<String> invalidBodies() {
        return Stream.of(
                // name 없음
                "{\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"a\"}",
                submissionBody("가".repeat(121), 30, "X", "MEME", "a"),
                submissionBody(uniq("n"), 30, "X", "MEME", "가".repeat(201)),
                submissionBody(uniq("n"), 30, "X", "NOPE", "a"),
                // confidence 없음
                "{\"name\":\"n\",\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\",\"disclosure\":false,\"oneLine\":\"a\"}");
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    @DisplayName("USR-SUB-05 필수 누락·길이 초과(name 121, oneLine 201)·잘못된 카테고리는 400")
    void invalidBodyIs400(String body) throws Exception {
        clock.set(T0);
        postSubmission(fx.user(), body).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-SUB-07 마감 지난 항목·JUDGING 항목 제보는 422 item-closed, 제보권 차감 없음")
    void closedItemIs422() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        UUID expired = fx.item(T0.minus(Duration.ofDays(15)));
        UUID judging = fx.item(T0.minus(Duration.ofDays(1)));
        fx.setState(judging, "JUDGING");

        for (UUID item : List.of(expired, judging)) {
            postSubmission(u, submissionBody(fx.key(item), 30, "X", "MEME", "설명"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.type").value("item-closed"));
        }
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(0));
    }

    @Test
    @DisplayName("USR-SUB-09 내 제보 목록은 내 것만 최신순, PENDING이면 judgeInDays")
    void mySubmissionsNewestFirst() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String first = uniq("mine1");
        String second = uniq("mine2");
        submit(u, first, 10);
        clock.set(T0.plusSeconds(60));
        submit(u, second, 50);
        submit(fx.user(), uniq("others"), 30);

        mvc.perform(get("/v1/submissions/me").with(asUser(u)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].word").value(second))
                .andExpect(jsonPath("$[1].word").value(first))
                .andExpect(jsonPath("$[0].judgeInDays").value(14));
    }

    @Test
    @DisplayName("USR-SUB-11 NFD로 분해된 한글 이름은 NFC 이름 항목에 합류")
    void nfdHangulJoinsNfcItem() throws Exception {
        clock.set(T0);
        String nfc = "두쫀쿠 " + UUID.randomUUID().toString().substring(0, 8);
        UUID item = itemOf(submit(fx.user(), nfc, 30));
        String nfd = Normalizer.normalize(nfc, Normalizer.Form.NFD);
        assertThat(nfd).isNotEqualTo(nfc);

        assertThat(itemOf(submit(fx.user(), nfd, 30))).isEqualTo(item);
    }

    @Test
    @DisplayName("USR-SUB-12 같은 유저·같은 새 이름 동시 2회 → 201 + 409, 500 없음, 1행")
    void concurrentSameSubmissionIsOneCreatedOneConflict() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String body = submissionBody(uniq("race"), 30, "X", "MEME", "설명");

        List<Integer> statuses = concurrently(2, () -> postSubmission(u, body).andReturn().getResponse().getStatus());

        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE user_id = ?", Integer.class, u))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("USR-SUB-13 이름 정확히 120자는 201")
    void name120CharsIsAccepted() throws Exception {
        clock.set(T0);
        String name = "가".repeat(111) + "-" + UUID.randomUUID().toString().substring(0, 8);
        assertThat(name).hasSize(120);

        submit(fx.user(), name, 30);
    }
}
```

- [ ] **Step 3: 실행**

Run: 실행 방법 명령, `<FILTER>` = `kr.trendstage.functional.user.*` (이 시점엔 두 클래스뿐).
Expected: 전부 PASS(이 태스크에는 BUG 예상 TC가 없다). 실패하면 Global Constraints의 결함 규칙대로 처리한다.

- [ ] **Step 4: 카탈로그 상태 갱신**

`docs/superpowers/specs/2026-10-05-functional-test-catalog.md`에서 USR-AUTH-01~04, USR-SUB-01~05·07·09·11~13의 상태를 `PASS`(또는 `BUG-n`)로 바꾼다.

- [ ] **Step 5: Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/user docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): USR-AUTH·USR-SUB — /v1 인증 공통과 제보

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: USR-TRD — 트렌드 조회·투표·인정

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserTrendTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.preferences`, `fx.setVisibility`, `fx.merged`, `fx.setState`, `fx.item`.

- [ ] **Step 1: 테스트 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserTrendTest extends FunctionalTestBase {

    private List<String> listIds(String json) {
        return read(json, "$.items[*].id");
    }

    @Test
    @DisplayName("USR-TRD-01 목록은 PENDING·JUDGING이고 PUBLIC인 항목만(MERGED·RESOLVED·TEMP_HIDDEN 제외)")
    void listShowsOnlyOpenPublicItems() throws Exception {
        clock.set(T0);
        UUID pending = itemOf(submit(fx.user(), uniq("pending"), 30));
        UUID judging = fx.item(T0);
        fx.setState(judging, "JUDGING");
        UUID merged = fx.item(T0);
        fx.merged(merged, pending);
        UUID resolved = fx.item(T0);
        fx.setState(resolved, "RESOLVED");
        UUID hidden = fx.item(T0);
        fx.setVisibility(hidden, "TEMP_HIDDEN");

        List<String> ids = listIds(getOk("/v1/trends", ANON));

        assertThat(ids).contains(pending.toString(), judging.toString())
                .doesNotContain(merged.toString(), resolved.toString(), hidden.toString());
    }

    @Test
    @DisplayName("USR-TRD-02 단계: 플랫폼 1종 SEED, 2종 RISING")
    void stageFollowsDistinctPlatforms() throws Exception {
        clock.set(T0);
        UUID seed = itemOf(submit(fx.user(), uniq("seedstage"), 30, "디시"));
        String rising = uniq("risingstage");
        UUID risingItem = itemOf(submit(fx.user(), rising, 30, "디시"));
        submit(fx.user(), rising, 30, "인스타");

        mvc.perform(get("/v1/trends/{id}", seed)).andExpect(jsonPath("$.stage").value("SEED"));
        mvc.perform(get("/v1/trends/{id}", risingItem)).andExpect(jsonPath("$.stage").value("RISING"));
    }

    @Test
    @DisplayName("USR-TRD-03 로그인 + daily=true는 최대 5개, 같은 날 두 번 호출하면 같은 목록, daily_selections 기록")
    void dailySelectionIsStableWithinDay() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        submit(fx.user(), uniq("daily"), 30);

        List<String> first = listIds(getOk("/v1/trends?daily=true", asUser(u)));
        List<String> second = listIds(getOk("/v1/trends?daily=true", asUser(u)));

        assertThat(first).isNotEmpty().hasSizeLessThanOrEqualTo(5).isEqualTo(second);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM daily_selections WHERE user_id = ?", Integer.class, u))
                .isEqualTo(first.size());
    }

    @Test
    @DisplayName("USR-TRD-04 오늘의 5개 선정 후 비공개·병합된 항목은 다시 조회하면 빠진다")
    void dailySelectionDropsHiddenOrMergedItems() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        fx.preferences(u, "CHALLENGE");   // 다른 테스트 항목은 MEME — 내 CHALLENGE 항목이 선정에 반드시 들어간다
        UUID toHide = itemOf(body(postSubmission(fx.user(),
                submissionBody(uniq("hideme"), 30, "X", "CHALLENGE", "설명")).andExpect(status().isCreated())));
        UUID toMerge = itemOf(body(postSubmission(fx.user(),
                submissionBody(uniq("mergeme"), 30, "X", "CHALLENGE", "설명")).andExpect(status().isCreated())));
        assertThat(listIds(getOk("/v1/trends?daily=true", asUser(u)))).contains(toHide.toString(), toMerge.toString());

        fx.setVisibility(toHide, "TEMP_HIDDEN");
        fx.merged(toMerge, fx.item(T0));

        assertThat(listIds(getOk("/v1/trends?daily=true", asUser(u))))
                .doesNotContain(toHide.toString(), toMerge.toString());
    }

    @Test
    @DisplayName("USR-TRD-05 상세: meaning=가장 이른 제보의 oneLine, pathText에 플랫폼, 투표 0이면 voteCount null")
    void detailBasics() throws Exception {
        clock.set(T0);
        String name = uniq("detail");
        UUID item = itemOf(body(postSubmission(fx.user(), submissionBody(name, 30, "디시", "MEME", "첫 설명"))
                .andExpect(status().isCreated())));
        clock.set(T0.plusSeconds(60));
        postSubmission(fx.user(), submissionBody(name, 30, "인스타", "MEME", "둘째 설명")).andExpect(status().isCreated());

        mvc.perform(get("/v1/trends/{id}", item))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meaning").value("첫 설명"))
                .andExpect(jsonPath("$.pathText").value(containsString("디시")))
                .andExpect(jsonPath("$.pathText").value(containsString("인스타")))
                .andExpect(jsonPath("$.voteCount").value(nullValue()))
                .andExpect(jsonPath("$.verdict").value(nullValue()));
    }

    @Test
    @DisplayName("USR-TRD-06 상세: 없는 id·MERGED·TEMP_HIDDEN·PERMANENT_HIDDEN은 404")
    void detailNotFoundCases() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID temp = fx.item(T0);
        fx.setVisibility(temp, "TEMP_HIDDEN");
        UUID perm = fx.item(T0);
        fx.setVisibility(perm, "PERMANENT_HIDDEN");

        for (UUID id : List.of(UUID.randomUUID(), merged, temp, perm)) {
            mvc.perform(get("/v1/trends/{id}", id)).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("USR-TRD-08 상세: 내가 워치한 항목은 watched=true, 다른 유저에게는 false")
    void detailWatchedFlag() throws Exception {
        clock.set(T0);
        String name = uniq("watched");
        UUID item = itemOf(submit(fx.user(), name, 30));
        UUID watcher = fx.user();
        postJson("/v1/me/watch", asUser(watcher), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        mvc.perform(get("/v1/trends/{id}", item).with(asUser(watcher))).andExpect(jsonPath("$.watched").value(true));
        mvc.perform(get("/v1/trends/{id}", item).with(asUser(fx.user()))).andExpect(jsonPath("$.watched").value(false));
    }

    @Test
    @DisplayName("USR-TRD-09 투표 true → false 토글: 둘 다 200, votes 1행, voteCount 문구 생김")
    void voteToggles() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("vote"), 30));
        UUID u = fx.user();

        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.willTrend").value(true))
                .andExpect(jsonPath("$.voteCount").value(notNullValue()));
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":false}", item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.willTrend").value(false));

        assertThat(jdbc.queryForList("SELECT will_trend FROM votes WHERE user_id = ? AND trend_item_id = ?",
                Boolean.class, u, item)).containsExactly(false);
    }

    @Test
    @DisplayName("USR-TRD-10 투표: willTrend 누락 400, 없는 항목·MERGED 404")
    void voteErrors() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("voteerr"), 30));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);
        UUID u = fx.user();

        postJson("/v1/trends/{id}/vote", asUser(u), "{}", item).andExpect(status().isBadRequest());
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", UUID.randomUUID()).andExpect(status().isNotFound());
        postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", merged).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-TRD-11 비공개(TEMP_HIDDEN·PERMANENT_HIDDEN) 항목 투표·인정은 404 (D1)")
    void hiddenItemInteractionsAre404() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        for (String visibility : List.of("TEMP_HIDDEN", "PERMANENT_HIDDEN")) {
            UUID item = fx.item(T0);
            fx.setVisibility(item, visibility);
            postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item).andExpect(status().isNotFound());
            mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u))).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("USR-TRD-12 인정: 첫 요청 201(바디 없음), 두 번째 409, endorsements 1행")
    void endorseOnce() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("endorse"), 30));
        UUID u = fx.user();

        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u)))
                .andExpect(status().isCreated())
                .andExpect(content().string(""));
        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(u))).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM endorsements WHERE user_id = ? AND trend_item_id = ?",
                Integer.class, u, item)).isEqualTo(1);
    }

    @Test
    @DisplayName("USR-TRD-13 인정: 없는 항목·MERGED는 404")
    void endorseNotFound() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID u = fx.user();

        mvc.perform(post("/v1/trends/{id}/endorse", UUID.randomUUID()).with(asUser(u))).andExpect(status().isNotFound());
        mvc.perform(post("/v1/trends/{id}/endorse", merged).with(asUser(u))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-TRD-14 그 항목에 제보한 유저의 인정은 409 (D2)")
    void submitterCannotEndorseOwnItem() throws Exception {
        clock.set(T0);
        UUID submitter = fx.user();
        UUID item = itemOf(submit(submitter, uniq("selfendorse"), 30));

        mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(submitter))).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("USR-TRD-15 같은 유저 투표·인정 동시 첫 요청: 500 없음, 투표 1행, 인정 201+409")
    void concurrentFirstVoteAndEndorse() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("racevote"), 30));
        UUID voter = fx.user();
        UUID endorser = fx.user();

        List<Integer> votes = concurrently(2, () -> postJson("/v1/trends/{id}/vote", asUser(voter),
                "{\"willTrend\":true}", item).andReturn().getResponse().getStatus());
        List<Integer> endorses = concurrently(2, () -> mvc.perform(post("/v1/trends/{id}/endorse", item)
                .with(asUser(endorser))).andReturn().getResponse().getStatus());

        assertThat(votes).containsOnly(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM votes WHERE user_id = ? AND trend_item_id = ?",
                Integer.class, voter, item)).isEqualTo(1);
        assertThat(endorses).containsExactlyInAnyOrder(201, 409);
    }
}
```

- [ ] **Step 2: 실행**

Run: `<FILTER>` = `kr.trendstage.functional.user.UserTrendTest`
Expected: USR-TRD-04(BUG-1), -11(BUG-3), -14(BUG-4)는 FAIL(각각 숨김·병합 항목이 목록에 남음 / 200·201 / 201). USR-TRD-15(BUG-2)는 FAIL 가능(동시성 — 경쟁이 안 일어나면 PASS할 수 있다. PASS면 3회 더 돌려 보고, 한 번이라도 실패하면 BUG-2로 확정, 4회 모두 PASS면 PASS로 기록하고 보고에 "재현 안 됨"을 적는다). 나머지 PASS.

- [ ] **Step 3: 확인된 실패에 `@Disabled` 달기**

실패를 확인한 메서드 위에 각각:

```java
    @org.junit.jupiter.api.Disabled("BUG-1: 오늘의 5개가 선정 후 비공개·병합된 항목을 그날 계속 노출 — TrendQueryService가 저장된 선정을 상태·공개 여부 재확인 없이 반환")
```
```java
    @org.junit.jupiter.api.Disabled("BUG-2: 같은 유저의 투표·인정 동시 첫 요청이 UNIQUE 위반으로 500")
```
```java
    @org.junit.jupiter.api.Disabled("BUG-3: 비공개 항목에도 투표·인정이 된다(D1 결정: 404)")
```
```java
    @org.junit.jupiter.api.Disabled("BUG-4: 제보자 본인이 자기 항목을 인정할 수 있다(D2 결정: 409)")
```

다시 실행해 나머지가 PASS이고 위 메서드만 skipped인지 확인한다.

- [ ] **Step 4: 카탈로그 상태 갱신** — USR-TRD-01~06·08~15를 `PASS`/`BUG-n`으로(USR-TRD-07은 Task 11에서).

- [ ] **Step 5: Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/user/UserTrendTest.java docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): USR-TRD — 트렌드 목록·상세·투표·인정 (BUG-1~4 기록)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: USR-RPT · USR-RD · USR-WCH — 신고·소명, 읽음, 워치

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserReportTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserReadWatchTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `asAdmin`, `fx.admin(role)`, `fx.report`, `fx.merged`, `fx.setVisibility`.
- 관리자 소명 요청: `POST /admin/reports/{id}/request-explanation` body `{"submissionId":"…","note":"…"}` (REVIEWER·OPERATOR·ADMIN), 결정: `POST /admin/reports/{id}/decide` body `{"decision":"RESTORE|HIDE_PERMANENT","note":"…"}` (OPERATOR·ADMIN). 소명 기한 = 요청 시각(`clock`) + 48h.

- [ ] **Step 1: `UserReportTest` 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserReportTest extends FunctionalTestBase {

    private String reportBody(UUID item, String reason, String detail) {
        return "{\"trendItemId\":\"" + item + "\",\"reason\":\"" + reason + "\",\"detail\":\"" + detail + "\"}";
    }

    private UUID fileReport(UUID reporter, UUID item) throws Exception {
        return uuid(body(postJson("/v1/reports", asUser(reporter), reportBody(item, "DEFAMATION", "허위"))
                .andExpect(status().isCreated())), "$.id");
    }

    /** 신고 → REVIEWER가 제보자 S의 제보를 지정해 소명 요청(기한 = 지금 + 48h). */
    private void requestExplanation(UUID report, UUID submissionId) throws Exception {
        postJson("/admin/reports/{id}/request-explanation", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER),
                "{\"submissionId\":\"" + submissionId + "\",\"note\":\"소명 요청\"}", report)
                .andExpect(status().isOk());
    }

    private String explanation(String text) {
        return "{\"text\":\"" + text + "\"}";
    }

    @Test
    @DisplayName("USR-RPT-01 신고 접수 → 201 OPEN, decision null, reports 1행")
    void fileReport() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("rpt"), 30));
        UUID reporter = fx.user();

        postJson("/v1/reports", asUser(reporter), reportBody(item, "OTHER", "설명"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.trendItemId").value(item.toString()))
                .andExpect(jsonPath("$.decision").value(nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reports WHERE reporter_id = ?", Integer.class, reporter))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("USR-RPT-02 신고: reason 누락·잘못된 값·detail 1001자 400, 없는 항목·MERGED 404")
    void reportErrors() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("rpterr"), 30));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);
        UUID u = fx.user();

        postJson("/v1/reports", asUser(u), "{\"trendItemId\":\"" + item + "\"}").andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(item, "NOPE", "x")).andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(item, "OTHER", "가".repeat(1001))).andExpect(status().isBadRequest());
        postJson("/v1/reports", asUser(u), reportBody(UUID.randomUUID(), "OTHER", "x")).andExpect(status().isNotFound());
        postJson("/v1/reports", asUser(u), reportBody(merged, "OTHER", "x")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-RPT-03 비공개 항목도 신고할 수 있다(201)")
    void hiddenItemCanBeReported() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.setVisibility(item, "TEMP_HIDDEN");

        postJson("/v1/reports", asUser(fx.user()), reportBody(item, "OTHER", "x")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("USR-RPT-04 내 신고 목록은 내 것만 최신순")
    void myReportsNewestFirst() throws Exception {
        clock.set(T0);
        UUID a = itemOf(submit(fx.user(), uniq("rpta"), 30));
        UUID b = itemOf(submit(fx.user(), uniq("rptb"), 30));
        UUID reporter = fx.user();
        UUID first = fileReport(reporter, a);
        UUID second = fileReport(reporter, b);
        fileReport(fx.user(), a);

        mvc.perform(get("/v1/reports/me").with(asUser(reporter)))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.toString()))
                .andExpect(jsonPath("$[1].id").value(first.toString()));
    }

    @Test
    @DisplayName("USR-RPT-05 받은 신고: 내 제보가 지정된 신고가 보이고 신고자 id는 없다, 제보 없는 유저는 빈 목록")
    void receivedReports() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("recv"), 30);
        UUID report = fileReport(fx.user(), itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        String json = getOk("/v1/reports/received", asUser(s));
        assertThat((List<String>) read(json, "$[*].id")).containsExactly(report.toString());
        assertThat(json).doesNotContain("reporterId");
        mvc.perform(get("/v1/reports/received").with(asUser(fx.user()))).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("USR-RPT-06 지정된 제보자가 EXPLAINING 신고에 소명 → 200, explanation_text 저장")
    void submitExplanation() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("expl"), 30);
        UUID report = fileReport(fx.user(), itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("사실입니다"), report).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("사실입니다");
    }

    @Test
    @DisplayName("USR-RPT-07 소명: 없는 신고 404, 지정 안 된 유저·지정 제보 없음 403, DECIDED 409, text 빈값·2001자 400")
    void explanationErrors() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("explerr"), 30);
        UUID item = itemOf(sub);
        UUID report = fileReport(fx.user(), item);
        requestExplanation(report, uuid(sub, "$.id"));
        UUID unassigned = fx.report(item, "OPEN", T0);

        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), UUID.randomUUID()).andExpect(status().isNotFound());
        postJson("/v1/reports/{id}/explanation", asUser(fx.user()), explanation("x"), report).andExpect(status().isForbidden());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), unassigned).andExpect(status().isForbidden());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation(""), report).andExpect(status().isBadRequest());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("가".repeat(2001)), report).andExpect(status().isBadRequest());

        postJson("/admin/reports/{id}/decide", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"decision\":\"RESTORE\",\"note\":\"오신고\"}", report).andExpect(status().isOk());
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("x"), report).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("USR-RPT-08 소명: 마감 전 재제출은 덮어쓰고(200), 마감(요청+48h) 후 제출은 409 (D3)")
    void explanationDeadline() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("expldl"), 30);
        UUID report = fileReport(fx.user(), itemOf(sub));
        requestExplanation(report, uuid(sub, "$.id"));

        clock.set(T0.plus(Duration.ofHours(1)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("첫 소명"), report).andExpect(status().isOk());
        clock.set(T0.plus(Duration.ofHours(2)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("고친 소명"), report).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("고친 소명");

        clock.set(T0.plus(Duration.ofHours(49)));
        postJson("/v1/reports/{id}/explanation", asUser(s), explanation("늦은 소명"), report).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT explanation_text FROM reports WHERE id = ?", String.class, report))
                .isEqualTo("고친 소명");
    }
}
```

`(List<String>) read(...)` 캐스트에 unchecked 경고가 나면 클래스에 `@SuppressWarnings("unchecked")`를 단다.

- [ ] **Step 2: `UserReadWatchTest` 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserReadWatchTest extends FunctionalTestBase {

    private List<Map<String, Object>> watches(UUID u) throws Exception {
        return read(getOk("/v1/me/watch", asUser(u)), "$");
    }

    @Test
    @DisplayName("USR-RD-01 읽음 기록 2번 → 204 두 번, 목록에 id 1개(멱등)")
    void markReadIsIdempotent() throws Exception {
        clock.set(T0);
        UUID item = itemOf(submit(fx.user(), uniq("read"), 30));
        UUID u = fx.user();
        String body = "{\"trendId\":\"" + item + "\"}";

        postJson("/v1/me/reads", asUser(u), body).andExpect(status().isNoContent());
        postJson("/v1/me/reads", asUser(u), body).andExpect(status().isNoContent());

        List<String> ids = read(getOk("/v1/me/reads", asUser(u)), "$");
        assertThat(ids).containsExactly(item.toString());
    }

    @Test
    @DisplayName("USR-RD-02 읽음: 없는 항목 404, trendId 누락 400")
    void markReadErrors() throws Exception {
        UUID u = fx.user();
        postJson("/v1/me/reads", asUser(u), "{\"trendId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
        postJson("/v1/me/reads", asUser(u), "{}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-WCH-01 존재하는 항목 이름 워치 → 201, 목록에 {keyword, stage}")
    void watchExistingKeyword() throws Exception {
        clock.set(T0);
        String name = uniq("wch");
        submit(fx.user(), name, 30);
        UUID u = fx.user();

        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        assertThat(watches(u)).singleElement().satisfies(w -> {
            assertThat(w.get("keyword")).isEqualTo(name);
            assertThat(w.get("stage")).isEqualTo("SEED");
        });
    }

    @Test
    @DisplayName("USR-WCH-02 대소문자만 다른 키워드 재워치 → 201, 목록 1개, 처음 keyword 유지")
    void rewatchIsNoOp() throws Exception {
        clock.set(T0);
        String name = uniq("wchdup");
        submit(fx.user(), name, 30);
        UUID u = fx.user();

        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name.toUpperCase() + "\"}").andExpect(status().isCreated());

        assertThat(watches(u)).singleElement().satisfies(w -> assertThat(w.get("keyword")).isEqualTo(name));
    }

    @Test
    @DisplayName("USR-WCH-03 존재하지 않는 키워드 404, 빈 키워드 400")
    void watchErrors() throws Exception {
        UUID u = fx.user();
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + uniq("nothing") + "\"}").andExpect(status().isNotFound());
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("USR-WCH-04 워치 해제는 있든 없든 204, 목록에서 빠진다")
    void unwatch() throws Exception {
        clock.set(T0);
        String name = uniq("unwch");
        submit(fx.user(), name, 30);
        UUID u = fx.user();
        postJson("/v1/me/watch", asUser(u), "{\"keyword\":\"" + name + "\"}").andExpect(status().isCreated());

        mvc.perform(delete("/v1/me/watch/{keyword}", name).with(asUser(u))).andExpect(status().isNoContent());
        mvc.perform(delete("/v1/me/watch/{keyword}", uniq("never")).with(asUser(u))).andExpect(status().isNoContent());

        assertThat(watches(u)).isEmpty();
    }
}
```

- [ ] **Step 3: 실행**

Run: `<FILTER>` = `kr.trendstage.functional.user.UserReportTest`, 이어서 `kr.trendstage.functional.user.UserReadWatchTest`
Expected: USR-RPT-08(BUG-5)만 FAIL — 마감 후 제출이 200(소명 텍스트가 "늦은 소명"으로 덮임). 나머지 PASS.

- [ ] **Step 4: BUG-5 `@Disabled`**

```java
    @org.junit.jupiter.api.Disabled("BUG-5: 소명 기한(explanation_deadline, 요청+48h)을 검사하지 않아 마감 후 제출도 200(D3 결정: 409)")
```

재실행해 나머지 PASS 확인.

- [ ] **Step 5: 카탈로그 갱신** — USR-RPT-01~08, USR-RD-01~02, USR-WCH-01~04.

- [ ] **Step 6: Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/user docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): USR-RPT·RD·WCH — 신고·소명, 읽음, 워치 (BUG-5 기록)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: USR-ME — 내 정보, PR 1

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/user/UserMeTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.ledgerRow`, `fx.verdict`, `fx.item`.

- [ ] **Step 1: 테스트 작성**

```java
package kr.trendstage.functional.user;

import kr.trendstage.functional.FunctionalTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserMeTest extends FunctionalTestBase {

    @Test
    @DisplayName("USR-ME-01 신규 유저 등급: L0, TI 0.4, 판정 0, 다음 L1, 요건 3개(판정 수·TI·AS) 각각 basis")
    void newUserGrade() throws Exception {
        mvc.perform(get("/v1/me/grade").with(asUser(fx.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("L0"))
                .andExpect(jsonPath("$.trustIndex").value(closeTo(0.4, 0.0001)))
                .andExpect(jsonPath("$.judgedCount").value(0))
                .andExpect(jsonPath("$.nextGrade").value("L1"))
                .andExpect(jsonPath("$.requirements[*].kind")
                        .value(containsInAnyOrder("JUDGED_COUNT", "TRUST_INDEX", "ACTIVE_SCORE")))
                .andExpect(jsonPath("$.requirements[*].basis").value(everyItem(notNullValue())));
    }

    @Test
    @DisplayName("USR-ME-02 신규 유저 원장은 빈 목록")
    void newUserLedgerIsEmpty() throws Exception {
        mvc.perform(get("/v1/me/ledger").with(asUser(fx.user())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    @DisplayName("USR-ME-03 제보 없는 수동 ADJ 원장 행은 word='계정 조정', kind=ADJ")
    void manualAdjustmentShowsAsAccountAdjustment() throws Exception {
        UUID u = fx.user();
        fx.ledgerRow(u, 5, 90, T0);

        mvc.perform(get("/v1/me/ledger").with(asUser(u)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].word").value("계정 조정"))
                .andExpect(jsonPath("$.items[0].kind").value("ADJ"))
                .andExpect(jsonPath("$.items[0].delta").value(closeTo(5.0, 0.0001)));
    }

    @Test
    @DisplayName("USR-ME-04 요약: 제보 1건 후 quotaUsed 1 / quotaMax 2(L0)")
    void summaryShowsQuota() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        submit(u, uniq("quota"), 30);

        mvc.perform(get("/v1/me/summary").with(asUser(u)))
                .andExpect(jsonPath("$.quotaUsed").value(1))
                .andExpect(jsonPath("$.quotaMax").value(2));
    }

    @Test
    @DisplayName("USR-ME-05 투표 정확도: 판정된 항목 투표만 집계(HIT에 true 적중, MISS에 true 빗나감, 미판정 제외)")
    void voteAccuracyCountsOnlyJudgedItems() throws Exception {
        clock.set(T0);
        UUID hit = fx.item(T0);
        fx.verdict(hit, "HIT");
        UUID miss = fx.item(T0);
        fx.verdict(miss, "MISS");
        UUID pending = fx.item(T0);
        UUID u = fx.user();
        for (UUID item : new UUID[]{hit, miss, pending}) {
            postJson("/v1/trends/{id}/vote", asUser(u), "{\"willTrend\":true}", item).andExpect(status().isOk());
        }

        mvc.perform(get("/v1/me/summary").with(asUser(u)))
                .andExpect(jsonPath("$.votesTotal").value(2))
                .andExpect(jsonPath("$.votesCorrect").value(1))
                .andExpect(jsonPath("$.voteHitRate").value(closeTo(0.5, 0.0001)));
    }

    @Test
    @DisplayName("USR-ME-06 온보딩 전 선호 조회는 404")
    void preferencesBeforeOnboardingIs404() throws Exception {
        mvc.perform(get("/v1/me/preferences").with(asUser(fx.user()))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("USR-ME-07 선호 저장 → 200 요청 그대로, 조회도 같다")
    void savePreferences() throws Exception {
        UUID u = fx.user();
        String body = "{\"categories\":[\"MEME\",\"SLANG\"],\"notifyHour\":9}";

        putJson("/v1/me/preferences", asUser(u), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").value(containsInAnyOrder("MEME", "SLANG")))
                .andExpect(jsonPath("$.notifyHour").value(9));
        mvc.perform(get("/v1/me/preferences").with(asUser(u)))
                .andExpect(jsonPath("$.categories").value(containsInAnyOrder("MEME", "SLANG")))
                .andExpect(jsonPath("$.notifyHour").value(9));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"categories\":[],\"notifyHour\":9}",
            "{\"categories\":[\"MEME\"],\"notifyHour\":24}",
            "{\"categories\":[\"MEME\"],\"notifyHour\":-1}",
            "{\"categories\":[\"NOPE\"],\"notifyHour\":9}"})
    @DisplayName("USR-ME-08 선호 저장: 빈 카테고리·notifyHour 범위 밖·잘못된 카테고리는 400")
    void invalidPreferencesAre400(String body) throws Exception {
        putJson("/v1/me/preferences", asUser(fx.user()), body).andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 2: 실행**

Run: `<FILTER>` = `kr.trendstage.functional.user.UserMeTest`
Expected: 전부 PASS.

- [ ] **Step 3: 유저 영역 전체 + 전체 스위트 실행**

Run: `<FILTER>` = `kr.trendstage.functional.user.*` → 그다음 `--tests` 없이 전체.
Expected: BUG로 `@Disabled`한 것 외 전부 PASS, 기존 테스트 회귀 없음.

- [ ] **Step 4: 카탈로그 갱신(USR-ME-01~08) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/user/UserMeTest.java docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): USR-ME — 등급·원장·요약·선호

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: PR 1 생성**

```bash
cd /d/PJ/TRD && git push -u origin functional-test-suite
```
`gh`(전체 경로, 메모 참조)로 PR 생성: base `main`, 제목 `test: 기능 테스트 스위트 1/3 — 기반 + 유저 API(/v1)`. 본문: 바뀐 것(기반 변경 3개, USR TC 수), 확인된 BUG 목록(번호·TC·한 줄), 실행 명령, 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`. 병합은 하지 않는다.

---

### Task 6: ADM-ME · ADM-ACC · ADM-BAT — 관리자 내 정보, 계정, 배치 수동 실행

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminMeAccountTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminBatchTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `asAdmin(UUID, AdminRole)`, `fx.admin(role)`, `fx.auditCount(action, targetId)`, `fx.item`, `fx.mergeCheckedAt`.

시작 전: `git checkout -b functional-test-suite-adm` (PR 1 브랜치에서 분기).

- [ ] **Step 1: `AdminMeAccountTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminMeAccountTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-ME-01 GET /admin/me는 4개 역할 모두 세션의 {id, loginId, displayName, role}")
    void meReturnsSessionPrincipal() throws Exception {
        for (AdminRole role : AdminRole.values()) {
            UUID id = fx.admin(role.name());
            mvc.perform(get("/admin/me").with(asAdmin(id, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(id.toString()))
                    .andExpect(jsonPath("$.loginId").value("t_" + id))
                    .andExpect(jsonPath("$.displayName").value("테스터"))
                    .andExpect(jsonPath("$.role").value(role.name()));
        }
    }

    @Test
    @DisplayName("ADM-ACC-01 계정 목록: ADMIN·AUDITOR 200, REVIEWER·OPERATOR 403")
    void accountListRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.ADMIN, AdminRole.AUDITOR}) {
            mvc.perform(get("/admin/accounts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.OPERATOR}) {
            mvc.perform(get("/admin/accounts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("ADM-ACC-02 ADMIN이 다른 계정 비활성화 → 200 disabledAt, 감사 ACCOUNT_DISABLE, 그 계정 다음 요청 401 session-revoked")
    void disableAccount() throws Exception {
        UUID admin = fx.admin();
        UUID target = fx.admin("OPERATOR");

        mvc.perform(post("/admin/accounts/{id}/disable", target).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disabledAt").value(notNullValue()));

        assertThat(fx.auditCount("ACCOUNT_DISABLE", target)).isEqualTo(1);
        mvc.perform(get("/admin/queues/summary").with(asAdmin(target, AdminRole.OPERATOR)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("session-revoked"));
    }

    @Test
    @DisplayName("ADM-ACC-03 비활성화: 자기 자신 403, 없는 계정 422, OPERATOR 호출 403")
    void disableErrors() throws Exception {
        UUID admin = fx.admin();
        mvc.perform(post("/admin/accounts/{id}/disable", admin).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/admin/accounts/{id}/disable", UUID.randomUUID()).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isUnprocessableEntity());
        UUID operator = fx.admin("OPERATOR");
        mvc.perform(post("/admin/accounts/{id}/disable", fx.admin("REVIEWER")).with(asAdmin(operator, AdminRole.OPERATOR)))
                .andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: `AdminBatchTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminBatchTest extends FunctionalTestBase {

    private static final String RUN = "/admin/batch-jobs/cluster-merge/run";

    private int triggerAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM admin_audit_log WHERE action = 'CLUSTER_MERGE_MANUAL_TRIGGER'",
                Integer.class);
    }

    @Test
    @DisplayName("ADM-BAT-01 임베딩 서비스 없이 수동 실행 → 200, 병합·큐·분리 0, 내 후보는 merge_checked_at NULL 유지, 감사 1행")
    void runWithoutEmbeddingServiceFailsSoftly() throws Exception {
        UUID candidate = fx.item(clock.instant());
        int before = triggerAudits();

        String json = body(mvc.perform(post(RUN).with(asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR)))
                .andExpect(status().isOk()));

        assertThat((Integer) read(json, "$.autoMerged")).isZero();
        assertThat((Integer) read(json, "$.queued")).isZero();
        assertThat((Integer) read(json, "$.separated")).isZero();
        assertThat((Integer) read(json, "$.failed")).isPositive();
        assertThat(fx.mergeCheckedAt(candidate)).isNull();
        assertThat(triggerAudits()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("ADM-BAT-02 잠금이 잡혀 있으면(실행 중) 409, 감사 행 증가 없음")
    void runWhileLockedIs409() throws Exception {
        jdbc.update("INSERT INTO shedlock (name, lock_until, locked_at, locked_by) "
                + "VALUES ('cluster_merge', now() + interval '10 minutes', now(), 'test') "
                + "ON CONFLICT (name) DO UPDATE SET lock_until = EXCLUDED.lock_until");
        try {
            int before = triggerAudits();
            mvc.perform(post(RUN).with(asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR))).andExpect(status().isConflict());
            assertThat(triggerAudits()).isEqualTo(before);
        } finally {
            releaseBatchLock("cluster_merge");
        }
    }

    @Test
    @DisplayName("ADM-BAT-03 REVIEWER·AUDITOR 실행은 403")
    void runRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            mvc.perform(post(RUN).with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isForbidden());
        }
    }
}
```

`shedlock` 컬럼(`name, lock_until, locked_at, locked_by`)은 ShedLock JDBC 표준 스키마다. 마이그레이션의 실제 컬럼명이 다르면(`grep -rn "CREATE TABLE shedlock" backend/persistence/src/main/resources/db/migration`) 그에 맞춘다.

- [ ] **Step 3: 실행** — `<FILTER>` = `kr.trendstage.functional.admin.AdminMeAccountTest`, `kr.trendstage.functional.admin.AdminBatchTest`. Expected: 전부 PASS.

- [ ] **Step 4: 카탈로그 갱신(ADM-ME-01, ADM-ACC-01~03, ADM-BAT-01~03) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/admin docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): ADM-ME·ACC·BAT — 관리자 내 정보, 계정 비활성화, cluster_merge 수동 실행

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: ADM-MQ — 병합 큐

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminMergeQueueTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.item/submission/seedSubmission/mergeQueueEntry/queueStatus/setState/itemState/itemOf/auditCount/handle/user/admin`.

- [ ] **Step 1: 테스트 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class AdminMergeQueueTest extends FunctionalTestBase {

    /** 병합 후보 한 쌍. old = 먼저 본 항목(생존 예정), new = 나중 항목. */
    private record Pair(UUID queue, UUID oldItem, UUID newItem) {}

    @BeforeEach
    void setClock() {
        clock.set(T0.plus(Duration.ofDays(1)));
    }

    private Pair pair() {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        fx.submission(fx.user(), oldItem, 30, T0);
        fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        return new Pair(fx.mergeQueueEntry(newItem, oldItem, 0.80), oldItem, newItem);
    }

    private ResultActions decide(UUID queue, String action, String key, UUID admin, AdminRole role) throws Exception {
        MockHttpServletRequestBuilder req = post("/admin/merge-queue/{id}/" + action, queue)
                .with(asAdmin(admin, role)).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"테스트\"}");
        if (key != null) req = req.header("Idempotency-Key", key);
        return mvc.perform(req);
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    @Test
    @DisplayName("ADM-MQ-01 큐 목록(4개 역할): 내 PENDING 행이 similarity·이름·orderPreview와 함께, 시딩은 '시딩 handle'")
    void listShowsPendingEntry() throws Exception {
        Pair p = pair();
        fx.seedSubmission(fx.user(), p.oldItem(), T0.plusSeconds(30));

        for (AdminRole role : AdminRole.values()) {
            String json = getOk("/admin/merge-queue", asAdmin(fx.admin(role.name()), role));
            List<Map<String, Object>> rows = read(json, "$[?(@.id == '" + p.queue() + "')]");
            assertThat(rows).singleElement().satisfies(row -> {
                assertThat(((Number) row.get("similarity")).doubleValue()).isEqualTo(0.80);
                assertThat(row.get("newName")).isEqualTo(fx.key(p.newItem()));
                assertThat(row.get("oldName")).isEqualTo(fx.key(p.oldItem()));
                assertThat((List<String>) row.get("orderPreview")).anySatisfy(e -> assertThat(e).startsWith("시딩"));
            });
        }
    }

    @Test
    @DisplayName("ADM-MQ-02 미리보기: 생존=먼저 본 항목, 같은 유저 중복은 VOID·제보권 반환 대상, 시딩은 순위 없음")
    void previewShowsMergeEffects() throws Exception {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        UUID u = fx.user();
        UUID v = fx.user();
        fx.submission(u, oldItem, 30, T0);
        fx.seedSubmission(fx.user(), oldItem, T0.plusSeconds(60));
        fx.submission(u, newItem, 30, T0.plus(Duration.ofHours(1)));
        fx.submission(v, newItem, 30, T0.plus(Duration.ofHours(2)));
        UUID queue = fx.mergeQueueEntry(newItem, oldItem, 0.80);

        String json = getOk("/admin/merge-queue/{id}/preview", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), queue);

        assertThat((List<String>) read(json, "$.dedupVoidedHandles")).containsExactly(fx.handle(u));
        assertThat((List<String>) read(json, "$.quotaRefundHandles")).containsExactly(fx.handle(u));
        List<Map<String, Object>> order = read(json, "$.orderRank");
        assertThat(order).anySatisfy(e -> {
            assertThat(e.get("seed")).isEqualTo(true);
            assertThat(e.get("rankAfter")).isNull();
        });
        assertThat(order).anySatisfy(e -> {
            assertThat(e.get("handle")).isEqualTo(fx.handle(v));
            assertThat(e.get("rankAfter")).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("ADM-MQ-03 미리보기: 없는 id·이미 처리된 항목은 422")
    void previewErrors() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        mvc.perform(get("/admin/merge-queue/{id}/preview", UUID.randomUUID()).with(asAdmin(reviewer, AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
        Pair p = pair();
        decide(p.queue(), "merge", key(), reviewer, AdminRole.REVIEWER).andExpect(status().isOk());
        mvc.perform(get("/admin/merge-queue/{id}/preview", p.queue()).with(asAdmin(reviewer, AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-MQ-04 병합 역할: REVIEWER·OPERATOR 200, AUDITOR 403")
    void mergeRoles() throws Exception {
        decide(pair().queue(), "merge", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER).andExpect(status().isOk());
        decide(pair().queue(), "merge", key(), fx.admin("OPERATOR"), AdminRole.OPERATOR).andExpect(status().isOk());
        decide(pair().queue(), "merge", key(), fx.admin("AUDITOR"), AdminRole.AUDITOR).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADM-MQ-05 같은 Idempotency-Key로 병합 두 번 → 두 번째 replayed=true, 감사 MERGE 1행")
    void sameKeyReplays() throws Exception {
        Pair p = pair();
        UUID reviewer = fx.admin("REVIEWER");
        String k = key();

        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(false));
        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));

        assertThat(fx.auditCount("MERGE", p.oldItem())).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-MQ-06 같은 키로 다른 결정은 422 idempotency-key-mismatch, 처리된 항목에 새 키는 409 merge-queue-decided")
    void keyMismatchAndDecided() throws Exception {
        Pair p = pair();
        UUID reviewer = fx.admin("REVIEWER");
        String k = key();
        decide(p.queue(), "merge", k, reviewer, AdminRole.REVIEWER).andExpect(status().isOk());

        decide(p.queue(), "separate", k, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("idempotency-key-mismatch"));
        decide(p.queue(), "merge", key(), reviewer, AdminRole.REVIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-queue-decided"));
    }

    @Test
    @DisplayName("ADM-MQ-07 판정된(RESOLVED) 항목과의 병합은 409 merge-resolved, 큐는 PENDING 유지")
    void resolvedTargetIsRejected() throws Exception {
        Pair p = pair();
        fx.setState(p.oldItem(), "RESOLVED");

        decide(p.queue(), "merge", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("merge-resolved"));
        assertThat(fx.queueStatus(p.queue())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("ADM-MQ-08 분리 → 200 SKIPPED, 감사 MERGE_SEPARATE, 두 항목·제보 변화 없음")
    void separateChangesNoData() throws Exception {
        UUID oldItem = fx.item(T0);
        UUID newItem = fx.item(T0.plus(Duration.ofHours(1)));
        UUID sub = fx.submission(fx.user(), newItem, 30, T0.plus(Duration.ofHours(1)));
        UUID queue = fx.mergeQueueEntry(newItem, oldItem, 0.80);

        decide(queue, "separate", key(), fx.admin("REVIEWER"), AdminRole.REVIEWER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SKIPPED"));

        assertThat(fx.auditCount("MERGE_SEPARATE", newItem)).isEqualTo(1);
        assertThat(fx.itemState(newItem)).isEqualTo("PENDING");
        assertThat(fx.itemState(oldItem)).isEqualTo("PENDING");
        assertThat(fx.itemOf(sub)).isEqualTo(newItem);
    }

    @Test
    @DisplayName("ADM-MQ-09 분리: 키 없으면 400 idempotency-key-required, 판정된 항목도 200(가드 없음)")
    void separateErrorsAndNoGuard() throws Exception {
        UUID reviewer = fx.admin("REVIEWER");
        decide(pair().queue(), "separate", null, reviewer, AdminRole.REVIEWER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("idempotency-key-required"));

        Pair p = pair();
        fx.setState(p.oldItem(), "RESOLVED");
        decide(p.queue(), "separate", key(), reviewer, AdminRole.REVIEWER).andExpect(status().isOk());
        assertThat(fx.itemState(p.oldItem())).isEqualTo("RESOLVED");
    }
}
```

- [ ] **Step 2: 실행** — `<FILTER>` = `kr.trendstage.functional.admin.AdminMergeQueueTest`. Expected: 전부 PASS. (`rankAfter` 같은 정수가 JsonPath에서 `Integer`로 온다. `Long`으로 와서 실패하면 `((Number) e.get("rankAfter")).intValue()`로 비교를 바꾼다 — 기대값은 그대로.)

- [ ] **Step 3: 카탈로그 갱신(ADM-MQ-01~09) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/admin/AdminMergeQueueTest.java docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): ADM-MQ — 병합 큐 목록·미리보기·병합·분리 HTTP 계약

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: ADM-PRM — 파라미터 스튜디오

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminParamStudioTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.admin(role)`, `fx.auditCount`.
- 드래프트는 전역 1개 — `@BeforeEach`·`@AfterEach`에서 DRAFT·REVIEW 드래프트를 지우고 그에 딸린 PENDING 승인 요청을 REJECTED로 닫는다.

- [ ] **Step 1: 테스트 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminParamStudioTest extends FunctionalTestBase {

    private static final String DRAFT = "/admin/params/draft";

    private UUID operator;
    private RequestPostProcessor op;

    @BeforeEach
    void setUp() throws Exception {
        clearActiveDrafts();
        operator = fx.admin("OPERATOR");
        op = asAdmin(operator, AdminRole.OPERATOR);
    }

    @AfterEach
    void clearActiveDrafts() {
        jdbc.update("UPDATE approval_requests SET status = 'REJECTED' WHERE action_type = 'PARAM_APPLY' AND status = 'PENDING' "
                + "AND target_ref IN (SELECT id FROM parameter_drafts WHERE status IN ('DRAFT', 'REVIEW'))");
        jdbc.update("DELETE FROM parameter_drafts WHERE status IN ('DRAFT', 'REVIEW')");
    }

    private UUID draftId() throws Exception {
        return uuid(getOk(DRAFT, op), "$.draftId");
    }

    private void simulate() throws Exception {
        mvc.perform(post(DRAFT + "/simulate").with(op)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-PRM-01 OPERATOR 조회 → 드래프트 생성(DRAFT), 드래프트 값 = 현재 값")
    void getCreatesDraft() throws Exception {
        String json = getOk(DRAFT, op);

        assertThat((String) read(json, "$.draftId")).isNotNull();
        assertThat((String) read(json, "$.status")).isEqualTo("DRAFT");
        assertThat((Integer) read(json, "$.submitterTarget")).isEqualTo((Integer) read(json, "$.currentSubmitterTarget"));
    }

    @Test
    @DisplayName("ADM-PRM-02 PUT {25, 0.25} → 값 반영, simResult 비워짐, 감사 PARAM_DRAFT_UPDATE")
    void putUpdatesAndClearsSimulation() throws Exception {
        UUID id = draftId();
        simulate();

        putJson(DRAFT, op, "{\"submitterTarget\":25,\"hitThreshold\":0.25}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submitterTarget").value(25))
                .andExpect(jsonPath("$.hitThreshold").value(closeTo(0.25, 1e-9)))
                .andExpect(jsonPath("$.simResult").value(nullValue()));
        assertThat(fx.auditCount("PARAM_DRAFT_UPDATE", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-03 PUT submitterTarget=0은 422")
    void putZeroTargetIs422() throws Exception {
        putJson(DRAFT, op, "{\"submitterTarget\":0,\"hitThreshold\":0.2}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-PRM-04 PUT hitThreshold -0.1·1.5·0은 422 (D5: 0 < hitThreshold ≤ 1)")
    void putThresholdOutOfRangeIs422() throws Exception {
        for (String t : new String[]{"-0.1", "1.5", "0"}) {
            putJson(DRAFT, op, "{\"submitterTarget\":20,\"hitThreshold\":" + t + "}")
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    @DisplayName("ADM-PRM-05 simulate → simResult 5개 필드, 감사 PARAM_SIMULATE")
    void simulateWritesResult() throws Exception {
        UUID id = draftId();

        mvc.perform(post(DRAFT + "/simulate").with(op))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simResult.changed").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.total").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.missToHit").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.hitToMiss").value(notNullValue()))
                .andExpect(jsonPath("$.simResult.reachChanged").value(notNullValue()));
        assertThat(fx.auditCount("PARAM_SIMULATE", id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-06 시뮬레이션 없이 승인 요청 422, PUT으로 시뮬레이션이 지워진 뒤에도 422")
    void approvalRequiresSimulation() throws Exception {
        draftId();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());

        simulate();
        putJson(DRAFT, op, "{\"submitterTarget\":22,\"hitThreshold\":0.2}").andExpect(status().isOk());
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-PRM-07 시뮬레이션 후 승인 요청 → 200 REVIEW, approval_requests PARAM_APPLY 1행")
    void requestApproval() throws Exception {
        UUID id = draftId();
        simulate();

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVIEW"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_requests WHERE action_type = 'PARAM_APPLY' "
                + "AND target_ref = ? AND status = 'PENDING'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-PRM-08 승인 요청: 사유 빈값 422, REVIEW 중 재요청 409, REVIEW 중 PUT 409")
    void approvalErrors() throws Exception {
        draftId();
        simulate();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\" \"}").andExpect(status().isUnprocessableEntity());
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isOk());

        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"다시\"}").andExpect(status().isConflict());
        putJson(DRAFT, op, "{\"submitterTarget\":30,\"hitThreshold\":0.2}").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("ADM-PRM-09 REVIEW 중 simulate는 409, sim_result 그대로 (D4)")
    void simulateDuringReviewIs409() throws Exception {
        UUID id = draftId();
        simulate();
        postJson(DRAFT + "/request-approval", op, "{\"reason\":\"보정\"}").andExpect(status().isOk());
        String before = jdbc.queryForObject("SELECT sim_result::text FROM parameter_drafts WHERE id = ?", String.class, id);

        mvc.perform(post(DRAFT + "/simulate").with(op)).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT sim_result::text FROM parameter_drafts WHERE id = ?", String.class, id))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("ADM-PRM-10 역할: REVIEWER는 GET·PUT·simulate·request-approval 403, AUDITOR는 PUT·simulate·request-approval 403")
    void roles() throws Exception {
        RequestPostProcessor reviewer = asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER);
        RequestPostProcessor auditor = asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR);

        mvc.perform(get(DRAFT).with(reviewer)).andExpect(status().isForbidden());
        for (RequestPostProcessor who : new RequestPostProcessor[]{reviewer, auditor}) {
            putJson(DRAFT, who, "{\"submitterTarget\":20,\"hitThreshold\":0.2}").andExpect(status().isForbidden());
            mvc.perform(post(DRAFT + "/simulate").with(who)).andExpect(status().isForbidden());
            postJson(DRAFT + "/request-approval", who, "{\"reason\":\"x\"}").andExpect(status().isForbidden());
        }
    }
}
```

- [ ] **Step 2: 실행** — `<FILTER>` = `kr.trendstage.functional.admin.AdminParamStudioTest`.
Expected: ADM-PRM-04(BUG-7: 200), ADM-PRM-09(BUG-6: 200)만 FAIL, 나머지 PASS. `approval_requests.status`·`action_type`이 텍스트 비교로 실패하면(enum 캐스트 오류) `status = 'REJECTED'::approval_status`처럼 캐스트를 붙인다.

- [ ] **Step 3: `@Disabled`**

```java
    @org.junit.jupiter.api.Disabled("BUG-7: hitThreshold 범위를 검증하지 않는다(D5 결정: 0 < hitThreshold ≤ 1, 밖이면 422)")
```
```java
    @org.junit.jupiter.api.Disabled("BUG-6: REVIEW(승인 대기) 드래프트도 simulate가 sim_result를 덮어쓴다(D4 결정: 409)")
```

재실행해 나머지 PASS 확인.

- [ ] **Step 4: 카탈로그 갱신(ADM-PRM-01~10) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/admin/AdminParamStudioTest.java docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): ADM-PRM — 파라미터 드래프트·시뮬레이션·승인 요청 (BUG-6·7 기록)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: ADM-RPT · ADM-SEED — 신고 대상 제보 목록, 시딩

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminReportSubmissionsTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminSeedTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.report/submission/seedSubmission/voidSubmission/item/setState/auditCount/admin`.
- 시딩 요청: `POST /admin/seed/submissions` body `{name, category(문자열), platform, evidenceUrl, confidence, oneLine}` (OPERATOR·ADMIN) → 200 `{submissionId, canonicalName, trendItemId}`.

- [ ] **Step 1: `AdminReportSubmissionsTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminReportSubmissionsTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-RPT-01 신고 대상 제보 목록: 비VOID 제보(시딩 포함) 생성순, 신고자 id 없음")
    void listsNonVoidSubmissionsInOrder() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        UUID first = fx.submission(fx.user(), item, 30, T0);
        UUID seed = fx.seedSubmission(fx.user(), item, T0.plusSeconds(60));
        UUID voided = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));
        fx.voidSubmission(voided, T0.plusSeconds(180));
        UUID report = fx.report(item, "OPEN", T0.plusSeconds(200));

        String json = getOk("/admin/reports/{id}/submissions", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), report);

        assertThat((List<String>) read(json, "$[*].submissionId")).containsExactly(first.toString(), seed.toString());
        assertThat(json).doesNotContain("reporterId");
    }

    @Test
    @DisplayName("ADM-RPT-02 없는 신고는 422")
    void unknownReportIs422() throws Exception {
        mvc.perform(get("/admin/reports/{id}/submissions", UUID.randomUUID())
                        .with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: `AdminSeedTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminSeedTest extends FunctionalTestBase {

    private static String seedBody(String name, int confidence, String category) {
        return """
                {"name":"%s","category":"%s","platform":"X","evidenceUrl":"https://example.com/s",\
                "confidence":%d,"oneLine":"시딩 설명"}""".formatted(name, category, confidence);
    }

    private ResultActions seed(UUID admin, AdminRole role, String body) throws Exception {
        return postJson("/admin/seed/submissions", asAdmin(admin, role), body);
    }

    @Test
    @DisplayName("ADM-SEED-01 OPERATOR 첫 시딩 → 200, 시딩 유저(seed_<loginId>) 생성·연결, is_seed=true, 감사 SEED_SUBMISSION_CREATE")
    void firstSeedCreatesSeedUser() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String name = uniq("seed");

        String json = body(seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isOk()));

        UUID submission = uuid(json, "$.submissionId");
        UUID item = uuid(json, "$.trendItemId");
        UUID seedUser = jdbc.queryForObject("SELECT seed_user_id FROM admin_accounts WHERE id = ?", UUID.class, op);
        String loginId = jdbc.queryForObject("SELECT login_id FROM admin_accounts WHERE id = ?", String.class, op);
        assertThat(fx.handle(seedUser)).isEqualTo("seed_" + loginId);
        assertThat(jdbc.queryForObject("SELECT is_seed FROM submissions WHERE id = ?", Boolean.class, submission)).isTrue();
        assertThat(jdbc.queryForObject("SELECT user_id FROM submissions WHERE id = ?", UUID.class, submission)).isEqualTo(seedUser);
        assertThat(fx.auditCount("SEED_SUBMISSION_CREATE", item)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-SEED-02 같은 관리자가 같은 항목을 다시 시딩하면 422")
    void reseedIs422() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String name = uniq("reseed");
        seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isOk());

        seed(op, AdminRole.OPERATOR, seedBody(name, 30, "MEME")).andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-SEED-03 확신도 20·잘못된 카테고리 422, 필수 누락 400")
    void seedValidation() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        seed(op, AdminRole.OPERATOR, seedBody(uniq("s"), 20, "MEME")).andExpect(status().isUnprocessableEntity());
        seed(op, AdminRole.OPERATOR, seedBody(uniq("s"), 30, "NOPE")).andExpect(status().isUnprocessableEntity());
        seed(op, AdminRole.OPERATOR, "{\"category\":\"MEME\",\"platform\":\"X\",\"evidenceUrl\":\"https://e\","
                + "\"confidence\":30,\"oneLine\":\"a\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ADM-SEED-04 같은 이름 유저 제보는 시딩 항목에 합류하고, 시딩이 먼저여도 유저가 1위")
    void userJoinsSeededItemAsFirstRank() throws Exception {
        clock.set(T0);
        String name = uniq("anchor");
        UUID item = uuid(body(seed(fx.admin("OPERATOR"), AdminRole.OPERATOR, seedBody(name, 30, "MEME"))
                .andExpect(status().isOk())), "$.trendItemId");

        clock.set(T0.plusSeconds(60));
        String res = submit(fx.user(), name, 30);

        assertThat(itemOf(res)).isEqualTo(item);
        assertThat((Integer) read(res, "$.orderRank")).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-SEED-05 JUDGING·RESOLVED 항목 시딩도 200 (D9: 현재 동작 고정)")
    void seedOnClosedItemIsAllowed() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        for (String state : new String[]{"JUDGING", "RESOLVED"}) {
            UUID item = fx.item(T0);
            fx.setState(item, state);
            seed(op, AdminRole.OPERATOR, seedBody(fx.key(item), 30, "MEME")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("ADM-SEED-06 시딩 정확도: HIT 1·MISS 1 → hit 1, miss 1, judged 2, TI (1+2)/(2+5)")
    void seedAccuracy() throws Exception {
        clock.set(T0);
        UUID op = fx.admin("OPERATOR");
        String displayName = uniq("시더");
        jdbc.update("UPDATE admin_accounts SET display_name = ? WHERE id = ?", displayName, op);
        UUID hit = uuid(body(seed(op, AdminRole.OPERATOR, seedBody(uniq("acc"), 30, "MEME"))), "$.submissionId");
        UUID miss = uuid(body(seed(op, AdminRole.OPERATOR, seedBody(uniq("acc"), 30, "MEME"))), "$.submissionId");
        jdbc.update("UPDATE submissions SET result = 'HIT', resolved_at = ? WHERE id = ?", Timestamp.from(T0), hit);
        jdbc.update("UPDATE submissions SET result = 'MISS', resolved_at = ? WHERE id = ?", Timestamp.from(T0), miss);

        String json = getOk("/admin/seed/accuracy", asAdmin(fx.admin("AUDITOR"), AdminRole.AUDITOR));
        List<Map<String, Object>> rows = read(json, "$[?(@.operatorName == '" + displayName + "')]");

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.get("hit")).isEqualTo(1);
            assertThat(r.get("miss")).isEqualTo(1);
            assertThat(r.get("judged")).isEqualTo(2);
            assertThat(((Number) r.get("trustIndex")).doubleValue()).isCloseTo(3.0 / 7.0, within(0.001));
        });
    }

    @Test
    @DisplayName("ADM-SEED-07 역할: REVIEWER·AUDITOR 시딩 403, 정확도 조회는 4개 역할 200")
    void seedRoles() throws Exception {
        clock.set(T0);
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            seed(fx.admin(role.name()), role, seedBody(uniq("r"), 30, "MEME")).andExpect(status().isForbidden());
        }
        for (AdminRole role : AdminRole.values()) {
            mvc.perform(get("/admin/seed/accuracy").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
    }
}
```

`(List<String>) read(...)` unchecked 경고는 `AdminReportSubmissionsTest`에 `@SuppressWarnings("unchecked")`로 처리한다.

- [ ] **Step 3: 실행** — 두 클래스. Expected: 전부 PASS. ADM-SEED-06에서 정확도가 `resolved_at`이 아닌 다른 조건(예: 판정 행)으로 세어 0이 나오면, 정확도 서비스(`AdminSeedService.accuracy`)의 집계 조건을 읽고 픽스처를 그 조건에 맞춘다(기대값 1/1/2는 유지).

- [ ] **Step 4: 카탈로그 갱신(ADM-RPT-01~02, ADM-SEED-01~07) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/admin docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): ADM-RPT·SEED — 신고 대상 제보 목록, 시딩 등록·정확도

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: ADM-ITM · ADM-VRD — 항목, 판정 목록·유예 연장, PR 2

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminTrendItemTest.java`
- Create: `backend/app/src/test/java/kr/trendstage/functional/admin/AdminVerdictTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase`, `fx.item/submission/seedSubmission/voidSubmission/merged/setState/verdictRow/deadlineOverride/setDeadlineOverride/itemState/auditCount/handle/admin`.
- 유예 연장: `POST /admin/verdicts/{trendItemId}/extend-grace` body `{"days":n,"reason":"…"}` (OPERATOR·ADMIN), 1~7일, 상한 first_seen + 21일.

- [ ] **Step 1: `AdminTrendItemTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminTrendItemTest extends FunctionalTestBase {

    @Test
    @DisplayName("ADM-ITM-01 항목 목록: 내 항목(MERGED 포함)이 submitterCount(시딩·VOID 제외)·currentResult와 함께")
    void listIncludesMyItems() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.submission(fx.user(), item, 30, T0);
        fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        fx.seedSubmission(fx.user(), item, T0.plusSeconds(90));
        UUID voided = fx.submission(fx.user(), item, 30, T0.plusSeconds(120));
        fx.voidSubmission(voided, T0.plusSeconds(150));
        UUID merged = fx.item(T0);
        fx.merged(merged, item);

        String json = getOk("/admin/trend-items", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER));

        List<Map<String, Object>> mine = read(json, "$[?(@.id == '" + item + "')]");
        assertThat(mine).singleElement().satisfies(r -> assertThat(r.get("submitterCount")).isEqualTo(2));
        List<Map<String, Object>> tomb = read(json, "$[?(@.id == '" + merged + "')]");
        assertThat(tomb).singleElement().satisfies(r -> assertThat(r.get("state")).isEqualTo("MERGED"));
    }

    @Test
    @DisplayName("ADM-ITM-02 미판정 항목 상세: deadline·daysLeft·제보자 수·플랫폼 수(시딩 제외)·인정 수, preview 채워짐, 1위 순위")
    void pendingDetail() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        UUID first = fx.user();
        fx.submission(first, item, 30, T0);
        fx.submission(fx.user(), item, 30, T0.plusSeconds(60));
        fx.seedSubmission(fx.user(), item, T0.plusSeconds(90));

        String json = getOk("/admin/trend-items/{id}", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), item);

        assertThat((String) read(json, "$.deadline")).isNotNull();
        assertThat(((Number) read(json, "$.daysLeft")).longValue()).isEqualTo(14);
        assertThat((Integer) read(json, "$.distinctSubmitters")).isEqualTo(2);
        assertThat((Integer) read(json, "$.distinctPlatforms")).isEqualTo(1);
        assertThat(((Number) read(json, "$.endorseCount")).longValue()).isZero();
        assertThat((String) read(json, "$.previewResult")).isEqualTo("MISS");
        assertThat((String) read(json, "$.currentResult")).isNull();
        List<Map<String, Object>> rows = read(json, "$.submissions[?(@.userHandle == '" + fx.handle(first) + "')]");
        assertThat(rows).singleElement().satisfies(r -> assertThat(r.get("orderRank")).isEqualTo(1));
    }

    @Test
    @DisplayName("ADM-ITM-03 판정된 항목 상세: current 채워짐, preview 없음")
    void judgedDetail() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.submission(fx.user(), item, 30, T0);
        fx.verdictRow(item);
        fx.setState(item, "RESOLVED");

        String json = getOk("/admin/trend-items/{id}", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER), item);

        assertThat((String) read(json, "$.currentResult")).isEqualTo("MISS");
        assertThat((String) read(json, "$.previewResult")).isNull();
    }

    @Test
    @DisplayName("ADM-ITM-04 없는 항목 상세는 422")
    void unknownItemIs422() throws Exception {
        mvc.perform(get("/admin/trend-items/{id}", UUID.randomUUID()).with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isUnprocessableEntity());
    }
}
```

- [ ] **Step 2: `AdminVerdictTest` 작성**

```java
package kr.trendstage.functional.admin;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SuppressWarnings("unchecked")
class AdminVerdictTest extends FunctionalTestBase {

    private ResultActions extend(UUID item, String body) throws Exception {
        return postJson("/admin/verdicts/{id}/extend-grace", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR), body, item);
    }

    @Test
    @DisplayName("ADM-VRD-01 판정 목록: 판정된 내 항목은 judged, 마감 임박 내 항목은 imminent")
    void listJudgedAndImminent() throws Exception {
        // 시계를 다른 테스트 데이터(2026-07 이후)보다 앞에 둬서 내 항목이 '가장 임박'하게 — imminent는 최대 30개
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        clock.set(now);
        UUID judged = fx.item(now.minus(Duration.ofDays(20)));
        fx.verdictRow(judged);
        fx.setState(judged, "RESOLVED");
        UUID imminent = fx.item(now.minus(Duration.ofDays(14)).plus(Duration.ofHours(1)));

        String json = getOk("/admin/verdicts", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR));

        assertThat((List<String>) read(json, "$.judged[*].trendItemId")).contains(judged.toString());
        assertThat((List<String>) read(json, "$.imminent[*].trendItemId")).contains(imminent.toString());
    }

    @Test
    @DisplayName("ADM-VRD-02 판정 목록 역할: OPERATOR·ADMIN·AUDITOR 200, REVIEWER 403")
    void listRoles() throws Exception {
        for (AdminRole role : new AdminRole[]{AdminRole.OPERATOR, AdminRole.ADMIN, AdminRole.AUDITOR}) {
            mvc.perform(get("/admin/verdicts").with(asAdmin(fx.admin(role.name()), role))).andExpect(status().isOk());
        }
        mvc.perform(get("/admin/verdicts").with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ADM-VRD-03 PENDING 항목 3일 연장 → 마감 D+17, 감사 VERDICT_GRACE_EXTEND")
    void extendThreeDays() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);

        extend(item, "{\"days\":3,\"reason\":\"병합 검수 지연\"}").andExpect(status().isOk());

        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(17)));
        assertThat(fx.auditCount("VERDICT_GRACE_EXTEND", item)).isEqualTo(1);
    }

    @Test
    @DisplayName("ADM-VRD-04 누적 D+21 초과분은 잘려 D+21(200), 이미 D+21이면 422")
    void extendCapsAtD21() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        fx.setDeadlineOverride(item, T0.plus(Duration.ofDays(20)));

        extend(item, "{\"days\":7,\"reason\":\"r\"}").andExpect(status().isOk());
        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(21)));

        extend(item, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-VRD-05 days 0·8, 판정된 항목, 없는 항목은 422 (1~7일)")
    void extendValidation() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        extend(item, "{\"days\":0,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
        extend(item, "{\"days\":8,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());

        UUID judged = fx.item(T0);
        fx.verdictRow(judged);
        extend(judged, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
        extend(UUID.randomUUID(), "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("ADM-VRD-06 JUDGING 항목을 연장해 새 마감이 미래면 PENDING 복귀")
    void extendReopensJudgingItem() throws Exception {
        UUID item = fx.item(T0);
        clock.set(T0.plus(Duration.ofDays(14)).plus(Duration.ofHours(1)));
        fx.setState(item, "JUDGING");

        extend(item, "{\"days\":3,\"reason\":\"r\"}").andExpect(status().isOk());

        assertThat(fx.itemState(item)).isEqualTo("PENDING");
        assertThat(fx.deadlineOverride(item)).isEqualTo(T0.plus(Duration.ofDays(17)));
    }

    @Test
    @DisplayName("ADM-VRD-07 사유 없이 연장해도 200(서버가 사유를 요구하지 않음)")
    void extendWithoutReason() throws Exception {
        clock.set(T0);
        extend(fx.item(T0), "{\"days\":2}").andExpect(status().isOk());
    }

    @Test
    @DisplayName("ADM-VRD-08 MERGED·VOID 항목 연장은 422, override 변화 없음 (D8)")
    void extendRejectsMergedAndVoid() throws Exception {
        clock.set(T0);
        UUID merged = fx.item(T0);
        fx.merged(merged, fx.item(T0));
        UUID voided = fx.item(T0);
        fx.setState(voided, "VOID");

        for (UUID item : List.of(merged, voided)) {
            extend(item, "{\"days\":1,\"reason\":\"r\"}").andExpect(status().isUnprocessableEntity());
            assertThat(fx.deadlineOverride(item)).isNull();
        }
    }

    @Test
    @DisplayName("ADM-VRD-09 연장 역할: REVIEWER·AUDITOR 403")
    void extendRoles() throws Exception {
        clock.set(T0);
        UUID item = fx.item(T0);
        for (AdminRole role : new AdminRole[]{AdminRole.REVIEWER, AdminRole.AUDITOR}) {
            postJson("/admin/verdicts/{id}/extend-grace", asAdmin(fx.admin(role.name()), role),
                    "{\"days\":1,\"reason\":\"r\"}", item).andExpect(status().isForbidden());
        }
    }
}
```

- [ ] **Step 3: 실행** — 두 클래스. Expected: ADM-VRD-08(BUG-8: 200)만 FAIL, 나머지 PASS. ADM-ITM-02의 `daysLeft`가 14가 아니라 13이면(올림/내림 차이) 서비스의 계산식을 읽어 확인하고, 문서가 정한 값이 없으므로 서비스 값으로 기대를 맞춘 뒤 카탈로그 비고에 "daysLeft = 내림"처럼 적는다.

- [ ] **Step 4: `@Disabled`**

```java
    @org.junit.jupiter.api.Disabled("BUG-8: 유예 연장이 MERGED·VOID 항목에도 된다(D8 결정: PENDING·JUDGING만, 그 외 422)")
```

- [ ] **Step 5: 관리자 영역 전체 + 전체 스위트 실행** — `<FILTER>` = `kr.trendstage.functional.admin.*` → `--tests` 없이 전체. Expected: `@Disabled` 외 PASS, 기존 회귀 없음.

- [ ] **Step 6: 카탈로그 갱신(ADM-ITM-01~04, ADM-VRD-01~09) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/admin docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): ADM-ITM·VRD — 항목 목록·상세, 판정 목록·유예 연장 (BUG-8 기록)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: PR 2 생성**

```bash
cd /d/PJ/TRD && git push -u origin functional-test-suite-adm
```
PR: base `functional-test-suite`(PR 1 브랜치, 스택), 제목 `test: 기능 테스트 스위트 2/3 — 관리자 API 빈 곳`. 본문: ADM TC 수, BUG-6~8, 실행 명령, 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

---

### Task 11: JRN-01~04 — HIT·MISS·시딩 제외·병합 중복 VOID

**Files:**
- Create: `backend/app/src/test/java/kr/trendstage/functional/journey/JourneyTest.java`

**Interfaces:**
- Consumes: `FunctionalTestBase` 전부, `asAdmin`, `fx.mergeQueueEntry`, `fx.admin`.
- Produces (Task 12가 같은 클래스에서 사용): `private Hit hitJourney()` — 4명(A c50, B c30, C c10, D c10)이 T0부터 1분 간격으로 제보 후 `AFTER_DEADLINE`에 판정. 반환 `record Hit(UUID item, List<UUID> users, List<UUID> submissions)`. 시계는 `AFTER_DEADLINE`에 남는다.

시작 전: `git checkout -b functional-test-suite-jrn` (PR 2 브랜치에서 분기).

점수(CLAUDE.md 공식, 기본 파라미터 target 20·threshold 0.2): 4명 → T = 0.2 → HIT L1(m = 0.2). Δ = c × w_order × 1.2 → A 50×1.0×1.2 = **60**, B 30×0.6×1.2 = **21.6**, C 10×0.4×1.2 = **4.8**, D 10×0.2×1.2 = **2.4**. 3명 → T = 0.15 → MISS, Δ = −c × 0.5.

- [ ] **Step 1: 테스트 작성(JRN-01~04)**

```java
package kr.trendstage.functional.journey;

import kr.trendstage.functional.FunctionalTestBase;
import kr.trendstage.persistence.type.AdminRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 카탈로그 JRN — 제보·조회는 HTTP, 판정·등급은 배치 직접 호출. 점수 기대값은 CLAUDE.md 공식. */
@SuppressWarnings("unchecked")
class JourneyTest extends FunctionalTestBase {

    private record Hit(UUID item, List<UUID> users, List<UUID> submissions) {}

    /** A c50 · B c30 · C c10 · D c10 이 1분 간격 제보(B는 공백·대소문자만 다른 이름) → 마감 직후 판정. */
    private Hit hitJourney() throws Exception {
        String name = "hit journey " + UUID.randomUUID().toString().substring(0, 8);
        String[] names = {name, "  " + name.toUpperCase() + " ", name, name};
        int[] confidences = {50, 30, 10, 10};
        List<UUID> users = new ArrayList<>();
        List<UUID> subs = new ArrayList<>();
        UUID item = null;
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plusSeconds(60L * i));
            UUID u = fx.user();
            String res = submit(u, names[i], confidences[i]);
            users.add(u);
            subs.add(uuid(res, "$.id"));
            item = itemOf(res);
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();
        return new Hit(item, users, subs);
    }

    private String ledger(UUID user) throws Exception {
        return getOk("/v1/me/ledger", asUser(user));
    }

    private String grade(UUID user) throws Exception {
        return getOk("/v1/me/grade", asUser(user));
    }

    @Test
    @DisplayName("JRN-01 HIT: 4명 → HIT L1, 원장 60/21.6/4.8/2.4, 내 제보·상세·등급에 반영")
    void hitJourneyScoresByOrder() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);

        assertThat(fx.itemState(h.item())).isEqualTo("RESOLVED");
        double[] expected = {60, 21.6, 4.8, 2.4};
        for (int i = 0; i < 4; i++) {
            String json = ledger(h.users().get(i));
            assertThat((List<String>) read(json, "$.items[*].kind")).containsExactly("HIT");
            assertThat(doubles(json, "$.items[*].delta")).containsExactly(expected[i]);
        }
        mvc.perform(get("/v1/submissions/me").with(asUser(a)))
                .andExpect(jsonPath("$[0].status").value("HIT"))
                .andExpect(jsonPath("$[0].orderRank").value(1))
                .andExpect(jsonPath("$[0].judgeInDays").doesNotExist());
        assertThat(doubles(getOk("/v1/submissions/me", asUser(a)), "$[*].delta")).containsExactly(60.0);
        mvc.perform(get("/v1/trends/{id}", h.item()))
                .andExpect(jsonPath("$.verdict").value("적중했어요"))
                .andExpect(jsonPath("$.reachLevel").value("L1"));
        String g = grade(a);
        assertThat((Integer) read(g, "$.judgedCount")).isEqualTo(1);
        assertThat(((Number) read(g, "$.trustIndex")).doubleValue()).isCloseTo(0.5, within(0.001));
        assertThat(((Number) read(g, "$.activeScore")).doubleValue()).isCloseTo(60.0, within(0.001));
    }

    @Test
    @DisplayName("JRN-02 MISS: 3명 → MISS, 원장 −25/−15/−5, A의 TI 0.333, 상세 '빗나갔어요'")
    void missJourneyPenalizesHalf() throws Exception {
        String name = uniq("miss journey");
        int[] confidences = {50, 30, 10};
        List<UUID> users = new ArrayList<>();
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * i));
            UUID u = fx.user();
            item = itemOf(submit(u, name, confidences[i]));
            users.add(u);
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        double[] expected = {-25, -15, -5};
        for (int i = 0; i < 3; i++) {
            assertThat(doubles(ledger(users.get(i)), "$.items[*].delta")).containsExactly(expected[i]);
        }
        assertThat(((Number) read(grade(users.get(0)), "$.trustIndex")).doubleValue()).isCloseTo(2.0 / 6.0, within(0.001));
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(jsonPath("$.verdict").value("빗나갔어요"));
    }

    @Test
    @DisplayName("JRN-03a 시딩이 가장 먼저여도 T·순위·원장에서 빠진다: 시딩 1 + 실유저 4 → HIT, 첫 실유저 +60(1위), 시딩 원장 없음")
    void seedIsExcludedFromRankAndLedger() throws Exception {
        clock.set(T0);
        String name = uniq("seed journey");
        UUID op = fx.admin("OPERATOR");
        String seedRes = body(postJson("/admin/seed/submissions", asAdmin(op, AdminRole.OPERATOR), """
                {"name":"%s","category":"MEME","platform":"X","evidenceUrl":"https://example.com/s",\
                "confidence":50,"oneLine":"앵커"}""".formatted(name)).andExpect(status().isOk()));
        UUID seedSub = uuid(seedRes, "$.submissionId");
        int[] confidences = {50, 30, 10, 10};
        UUID first = null;
        UUID item = null;
        for (int i = 0; i < 4; i++) {
            clock.set(T0.plusSeconds(60L * (i + 1)));
            UUID u = fx.user();
            if (i == 0) first = u;
            item = itemOf(submit(u, name, confidences[i]));
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ? AND supersedes IS NULL",
                String.class, item)).isEqualTo("HIT");
        assertThat(doubles(ledger(first), "$.items[*].delta")).containsExactly(60.0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM score_ledger WHERE submission_id = ?", Integer.class, seedSub))
                .isZero();
        String ranks = jdbc.queryForObject("SELECT evidence_json->'orderRanks' FROM verdicts WHERE trend_item_id = ?",
                String.class, item);
        assertThat(ranks).doesNotContain(seedSub.toString());
    }

    @Test
    @DisplayName("JRN-03b 관리자 3명 시딩 + 실유저 3명 → MISS (시딩만으로 HIT를 만들 수 없다)")
    void seedsCannotManufactureHit() throws Exception {
        clock.set(T0);
        String name = uniq("seed cartel");
        for (int i = 0; i < 3; i++) {
            postJson("/admin/seed/submissions", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR), """
                    {"name":"%s","category":"MEME","platform":"X","evidenceUrl":"https://example.com/s",\
                    "confidence":30,"oneLine":"앵커"}""".formatted(name)).andExpect(status().isOk());
        }
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * (i + 1)));
            item = itemOf(submit(fx.user(), name, 30));
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        assertThat(jdbc.queryForObject("SELECT result::text FROM verdicts WHERE trend_item_id = ? AND supersedes IS NULL",
                String.class, item)).isEqualTo("MISS");
        assertThat(jdbc.queryForObject("SELECT evidence_json->>'distinctSubmitters' FROM verdicts WHERE trend_item_id = ?",
                String.class, item)).isEqualTo("3");
    }

    @Test
    @DisplayName("JRN-04 병합 후 같은 유저 중복 → 늦은 제보 VOID → 제보권 1장 반환, 새 제보 가능")
    void mergeDedupRefundsQuota() throws Exception {
        clock.set(T0);
        UUID u = fx.user();
        String base = uniq("alpha");
        UUID x = itemOf(submit(u, base, 30));
        clock.set(T0.plus(Duration.ofHours(1)));
        String late = submit(u, base + " 2", 30);
        UUID y = itemOf(late);
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(2));
        UUID queue = fx.mergeQueueEntry(y, x, 0.80);

        clock.set(T0.plus(Duration.ofHours(2)));
        mvc.perform(post("/admin/merge-queue/{id}/merge", queue)
                        .with(asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER))
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"같은 밈\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.survivorId").value(x.toString()));

        List<Map<String, Object>> lateRow = read(getOk("/v1/submissions/me", asUser(u)),
                "$[?(@.id == '" + uuid(late, "$.id") + "')]");
        assertThat(lateRow).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo("VOID"));
        mvc.perform(get("/v1/me/summary").with(asUser(u))).andExpect(jsonPath("$.quotaUsed").value(1));
        submit(u, uniq("after refund"), 30);
    }
}
```

- [ ] **Step 2: 실행** — `<FILTER>` = `kr.trendstage.functional.journey.JourneyTest`. Expected: 전부 PASS. (`uniq("miss journey")` 같이 공백이 든 이름은 정규화돼도 유일하다.) 원장 값이 다르면 기대값을 바꾸지 말고 `evidence_json`(순위·T)을 조회해 원인을 보고한다 — 점수 공식 위반이면 BUG.

- [ ] **Step 3: 카탈로그 갱신(JRN-01~04, USR-SUB-10, USR-TRD-07은 JRN-01·02로 PASS) 후 Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/journey docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): JRN-01~04 — HIT·MISS 점수, 시딩 제외, 병합 중복 VOID·제보권 반환

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: JRN-05~09 — 재판정·VOID·투표 격리·등급·신고, 문서 정정, PR 3

**Files:**
- Modify: `backend/app/src/test/java/kr/trendstage/functional/journey/JourneyTest.java`
- Modify: `05-screen-endpoint-map.md:106`
- Modify: `docs/superpowers/specs/2026-10-05-functional-test-catalog.md`

**Interfaces:**
- Consumes: Task 11의 `hitJourney()`, `ledger(UUID)`, `grade(UUID)`, `record Hit`. `fx.voidSubmission`, `fx.judgedSubmission`, `fx.ledgerRow`, `fx.ledgerSum`, `fx.admin`.

- [ ] **Step 1: `JourneyTest`에 메서드 추가**

클래스 끝(마지막 `}` 앞)에 추가한다. 필요한 import 추가: `java.time.ZonedDateTime`, `static org.hamcrest.Matchers.not`, `static org.hamcrest.Matchers.hasItem`, `static org.hamcrest.Matchers.nullValue`.

```java
    @Test
    @DisplayName("JRN-05 재판정: D의 제보 VOID 후 재판정 → 차액 133.8 > 100이라 202(아무것도 안 씀) → 승인 → MISS, A 원장 = HIT 60 그대로 + ADJ −85")
    void rejudgeGoesThroughApprovalAndAppendsOnly() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);
        UUID aSub = h.submissions().get(0);
        fx.voidSubmission(h.submissions().get(3), AFTER_DEADLINE.plusSeconds(10));
        int rowsBefore = fx.ledgerRows(h.item());

        String res = body(postJson("/admin/verdicts/{id}/rejudge", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"reason\":\"중복 계정\"}", h.item()).andExpect(status().isAccepted()));
        assertThat(fx.ledgerRows(h.item())).isEqualTo(rowsBefore);

        mvc.perform(post("/admin/approvals/{id}/approve", uuid(res, "$.approvalRequestId"))
                        .with(asAdmin(fx.admin(), AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXECUTED"));

        assertThat(doubles(ledger(a), "$.items[*].delta")).containsExactlyInAnyOrder(60.0, -85.0);
        assertThat(fx.ledgerSum(a, h.item()).doubleValue()).isCloseTo(-25.0, within(0.0001));
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ? AND kind = 'HIT'",
                java.math.BigDecimal.class, aSub).doubleValue()).isCloseTo(60.0, within(0.0001));
        assertThat(fx.ledgerRows(h.item())).isGreaterThan(rowsBefore);
        mvc.perform(get("/v1/trends/{id}", h.item())).andExpect(jsonPath("$.verdict").value("빗나갔어요"));
    }

    @Test
    @DisplayName("JRN-06 판정된 항목 VOID: 차액 88.8 ≤ 100이라 즉시 → 유저별 원장 합 0, 원래 행 불변, 제보 VOID, 등급 판정 0·AS 0")
    void voidingJudgedItemZeroesScore() throws Exception {
        Hit h = hitJourney();
        UUID a = h.users().get(0);

        postJson("/admin/verdicts/{id}/void", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"reason\":\"어뷰징\"}", h.item())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPLIED"));

        for (UUID u : h.users()) {
            assertThat(fx.ledgerSum(u, h.item()).doubleValue()).isCloseTo(0.0, within(0.0001));
        }
        assertThat(jdbc.queryForObject("SELECT delta FROM score_ledger WHERE submission_id = ? AND kind = 'HIT'",
                java.math.BigDecimal.class, h.submissions().get(0)).doubleValue()).isCloseTo(60.0, within(0.0001));
        mvc.perform(get("/v1/submissions/me").with(asUser(a))).andExpect(jsonPath("$[0].status").value("VOID"));
        String g = grade(a);
        assertThat((Integer) read(g, "$.judgedCount")).isZero();
        assertThat(((Number) read(g, "$.activeScore")).doubleValue()).isCloseTo(0.0, within(0.001));
        mvc.perform(get("/v1/trends/{id}", h.item())).andExpect(jsonPath("$.verdict").value(nullValue()));
    }

    @Test
    @DisplayName("JRN-07 투표·인정은 판정에 안 들어간다(R1): 제보자 3명 + 30명 '뜬다' 투표·인정 → MISS, T 0.15, 투표자 적중 0/1")
    void votesAndEndorsementsDoNotAffectVerdict() throws Exception {
        String name = uniq("hype");
        UUID item = null;
        for (int i = 0; i < 3; i++) {
            clock.set(T0.plusSeconds(60L * i));
            item = itemOf(submit(fx.user(), name, 30));
        }
        clock.set(T0.plus(Duration.ofHours(1)));
        UUID voter = null;
        for (int i = 0; i < 30; i++) {
            voter = fx.user();
            postJson("/v1/trends/{id}/vote", asUser(voter), "{\"willTrend\":true}", item).andExpect(status().isOk());
            mvc.perform(post("/v1/trends/{id}/endorse", item).with(asUser(voter))).andExpect(status().isCreated());
        }
        clock.set(AFTER_DEADLINE);
        runVerdicts();

        Map<String, Object> v = jdbc.queryForMap("SELECT result::text AS result, score_t, "
                + "evidence_json->>'distinctSubmitters' AS n FROM verdicts WHERE trend_item_id = ?", item);
        assertThat(v.get("result")).isEqualTo("MISS");
        assertThat(((Number) v.get("score_t")).doubleValue()).isCloseTo(0.15, within(0.0001));
        assertThat(v.get("n")).isEqualTo("3");
        mvc.perform(get("/v1/me/summary").with(asUser(voter)))
                .andExpect(jsonPath("$.votesTotal").value(1))
                .andExpect(jsonPath("$.votesCorrect").value(0));
    }

    @Test
    @DisplayName("JRN-08 주간 등급 재계산(R3 AND): A(판정 5·TI 0.7·AS≥30) → L1·제보권 3, B(판정 5·AS 충분·TI 0.2) → L0")
    void weeklyGradeRecalcRequiresAllConditions() throws Exception {
        Hit h = hitJourney();   // A의 HIT 1건(+60) — 시계는 AFTER_DEADLINE(2026-10-05 월 10:00 KST)
        UUID a = h.users().get(0);
        UUID b = fx.user();
        for (int i = 0; i < 4; i++) fx.judgedSubmission(a, "HIT", T0.minus(Duration.ofDays(30)));
        fx.ledgerRow(a, 10, 90, T0);
        for (int i = 0; i < 5; i++) fx.judgedSubmission(b, "MISS", T0.minus(Duration.ofDays(30)));
        fx.ledgerRow(b, 200, 90, T0);

        clock.set(ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, KST).toInstant());   // 다음 월요일 00:00 KST
        runGradeRecalc();

        mvc.perform(get("/v1/me/grade").with(asUser(a))).andExpect(jsonPath("$.grade").value("L1"));
        mvc.perform(get("/v1/me/summary").with(asUser(a))).andExpect(jsonPath("$.quotaMax").value(3));
        mvc.perform(get("/v1/me/grade").with(asUser(b))).andExpect(jsonPath("$.grade").value("L0"));
        mvc.perform(get("/v1/me/summary").with(asUser(b))).andExpect(jsonPath("$.quotaMax").value(2));
    }

    @Test
    @DisplayName("JRN-09 신고 → 소명 요청 → 소명 → 영구 비공개: 신고자에게 결정 표시, 상세 404, 목록에서 빠짐")
    void reportExplanationDecision() throws Exception {
        clock.set(T0);
        UUID s = fx.user();
        String sub = submit(s, uniq("defame"), 30);
        UUID item = itemOf(sub);
        UUID reporter = fx.user();
        UUID report = uuid(body(postJson("/v1/reports", asUser(reporter),
                "{\"trendItemId\":\"" + item + "\",\"reason\":\"DEFAMATION\",\"detail\":\"허위 사실\"}")
                .andExpect(status().isCreated())), "$.id");

        postJson("/admin/reports/{id}/request-explanation", asAdmin(fx.admin("REVIEWER"), AdminRole.REVIEWER),
                "{\"submissionId\":\"" + uuid(sub, "$.id") + "\",\"note\":\"소명 요청\"}", report).andExpect(status().isOk());
        assertThat((List<String>) read(getOk("/v1/reports/received", asUser(s)), "$[*].id")).contains(report.toString());
        clock.set(T0.plus(Duration.ofHours(1)));
        postJson("/v1/reports/{id}/explanation", asUser(s), "{\"text\":\"근거 있습니다\"}", report).andExpect(status().isOk());
        postJson("/admin/reports/{id}/decide", asAdmin(fx.admin("OPERATOR"), AdminRole.OPERATOR),
                "{\"decision\":\"HIDE_PERMANENT\",\"note\":\"명예훼손 확인\"}", report).andExpect(status().isOk());

        List<Map<String, Object>> mine = read(getOk("/v1/reports/me", asUser(reporter)), "$[?(@.id == '" + report + "')]");
        assertThat(mine).singleElement().satisfies(r -> {
            assertThat(r.get("status")).isEqualTo("DECIDED");
            assertThat(r.get("decision")).isEqualTo("HIDE_PERMANENT");
        });
        mvc.perform(get("/v1/trends/{id}", item)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/trends")).andExpect(jsonPath("$.items[*].id").value(not(hasItem(item.toString()))));
    }
```

- [ ] **Step 2: 실행** — `<FILTER>` = `kr.trendstage.functional.journey.JourneyTest`. Expected: 전부 PASS.
  - JRN-05가 202가 아니라 200이면 차액 합을 `approval_requests`/응답 `adjTotal`로 확인한다(예상 133.8). 공식대로 133.8인데 200이면 승인 게이트 BUG, 값이 다르면 점수 계산 원인을 보고한다.
  - JRN-08에서 A가 L0이면 `/v1/me/grade`의 `requirements`(current·required·met)를 출력해 어느 조건이 빠졌는지 보고한다 — 기대값은 바꾸지 않는다.

- [ ] **Step 3: 문서 오류 정정** — `05-screen-endpoint-map.md:106`에서 `1~90일(\`MAX_GRACE_DAYS\`)`를 `1~7일(\`MAX_GRACE_DAYS\`, 누적 상한 최초 제보 + 21일)`로 바꾼다.

- [ ] **Step 4: 카탈로그 최종 갱신** — JRN-05~09 상태, 모든 TC의 상태가 `PASS`/`BUG-n`/`PASS(기존)` 중 하나인지 확인(`TODO`가 남으면 안 된다). 카탈로그 맨 아래에 "## BUG 목록" 절을 추가해 확인된 BUG-n마다 TC·현상·관련 코드 위치 한 줄씩.

- [ ] **Step 5: 최종 검증 — 두 가지 실행 순서 (Review Focus 1)**

Run 1: `<FILTER>` = `kr.trendstage.functional.*` (기능 패키지 단독).
Run 2: `--tests` 없이 전체 스위트.
Expected: 두 실행 모두 `BUILD SUCCESSFUL`, `@Disabled`(BUG) 외 실패 0. 한쪽에서만 실패하면 공유 DB 순서 의존이다 — 해당 테스트의 단언 범위를 자기 id로 좁혀 고친다(기대값은 그대로).

- [ ] **Step 6: Commit**

```bash
cd /d/PJ/TRD && git add backend/app/src/test/java/kr/trendstage/functional/journey 05-screen-endpoint-map.md docs/superpowers/specs/2026-10-05-functional-test-catalog.md && git commit -m "test(functional): JRN-05~09 — 재판정 승인·원장 불변, VOID, 투표 격리, 등급 AND, 신고·소명 / docs: 유예 연장 1~7일 정정

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 7: PR 3 생성 및 정리**

```bash
cd /d/PJ/TRD && git push -u origin functional-test-suite-jrn
```
PR: base `functional-test-suite-adm`, 제목 `test: 기능 테스트 스위트 3/3 — 여정(JRN) + 문서 정정`. 본문: JRN 9개 요약, 전체 BUG 목록(카탈로그 "BUG 목록" 절과 같음), 두 실행 순서 결과, 끝에 `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.

마지막으로 Gradle 캐시 볼륨을 지운다: `docker volume rm trd-gradle-test-cache`.
