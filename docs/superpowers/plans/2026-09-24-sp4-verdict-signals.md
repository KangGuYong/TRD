# SP4 판정 신호 재설계 + 백테스트 도구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 판정을 "유효 T = 제보자 비율 × 지속성 × 다양성"(독립성 압축·상대 목표치 포함)으로 바꾸되 기본값에서는 판정이 한 건도 달라지지 않게 하고, 값을 근거 있게 정할 백테스트 탭을 파라미터 스튜디오에 붙인다.

**Architecture:** 공식은 `domain-core`의 순수 함수(`VerdictEngine.breakdown` → `TBreakdown`)에 모으고 `SignalAxes`(NEUTRAL 기본)로 주입한다. 플랫폼은 근거 링크 도메인으로 `PlatformResolver`가 판별해 `submissions.platform`에 저장하고, 기기·IP는 `SignalHasher`(HMAC)로 해시만 저장한다. 판정 근거에는 해시 대신 판정 내 그룹 번호·활성 제보자 수·T 분해를 동결한다. 백테스트는 같은 엔진을 쓰는 `Backtest.run`(순수) + 불변 보관 `backtest_datasets` + 스튜디오 API/탭이며, 승인 요청은 시뮬레이션·백테스트 결과가 둘 다 있어야 한다.

**Tech Stack:** Java 17, Spring Boot 3.3.5(Spring Security 6, Data JPA/Hibernate 6, Jackson 2.17), Flyway(SQL + Java 마이그레이션), PostgreSQL 16 + pgvector, Testcontainers 1.21, JUnit 5, AssertJ, MockMvc; React 18 + Vite + TanStack Query(관리자 콘솔); Expo 54 + React Native 0.81(앱).

**Spec:** `docs/superpowers/specs/2026-09-24-sp4-verdict-signals-design.md` (결정 S1~S12)

## Global Constraints

- 브랜치 `sp4-verdict-signals`. 시작 전 `git branch --show-current`로 확인하고 다른 브랜치로 바꾸지 않는다. `main`은 소유자만 병합한다 — PR까지만.
- **`git add -A`·`git add .` 금지.** 파일명을 지정한다. `backend/app/.env`(있다면)는 읽지도 고치지도 않는다.
- V1~V30은 수정하지 않는다. SP4 스키마 변경은 `V31__sp4_verdict_signals.sql`, `V31_1__backfill_submission_platform.java`, `V31_2__submission_platform_constraints.sql` 셋.
- 커밋 메시지 끝에 반드시: `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`
- 결정 S1~S12를 구현 중에 바꾸지 않는다. 계획대로 되지 않으면 멈추고 보고한다.
- **기본값은 중립**(S6): `targetFloor 20`, `targetRatio 0`, `activeWindowDays 28`, `hitThreshold 0.20`, `persistenceFloor 1.0`, `persistenceFullDays 5`, `diversityFloor 1.0`, `diversityFullPlatforms 3`, `independenceMode OFF`. 이 값에서 새 엔진의 결과·T가 옛 공식(`clip(서로 다른 비시딩 제보자 / 20, 0, 1)`)과 **비트 단위로 같아야** 한다.
- 허용 범위(§5.1): `targetFloor ≥ 1`, `targetRatio 0~1`, `activeWindowDays 7~90`, `hitThreshold 0~1`, `persistenceFloor 0~1`, `persistenceFullDays 1~14`, `diversityFloor 0~1`, `diversityFullPlatforms 2~11`, `independenceMode ∈ {OFF, DEVICE, DEVICE_OR_IP}`. 위반 메시지는 **필드명으로 시작**한다(`persistenceFloor: 0 ~ 1 사이여야 합니다 (입력 1.5)`).
- 플랫폼 코드(정확히 11개, 이 순서): `DCINSIDE, THEQOO, FMKOREA, INSTIZ, X, INSTAGRAM, THREADS, YOUTUBE, TIKTOK, NAVER, ETC`.
- 기기 헤더 `X-Device-Id`(최대 128자). 해시 = `HMAC-SHA256(signal.hash-secret, "device:" + id)` / `("ip:" + 키)`, 소문자 hex 64자. IPv4는 주소 전체, IPv6는 앞 /64. **원문은 저장·로그·예외 메시지 어디에도 남기지 않는다.**
- **검증 오류 상태 코드는 422**(기존 `AdminValidationException`·`SubmissionValidationException` 매핑). 스펙 본문의 "400"은 이 규칙으로 읽는다(Ruling R1 — 스펙 §5·§4·§10에 반영됨).
- 해시 컬럼은 `VARCHAR(64)` + 소문자 hex CHECK(Ruling R2 — Hibernate `validate`가 `CHAR`(bpchar)를 String과 맞추지 못한다).
- 스펙 테스트 12("SP4 이전 형식 근거의 판정을 재판정해도 불변")는 두 테스트로 나눠 건다(Ruling R3 — 옛 형식 판정 행과 그에 맞는 원장 행을 손으로 만드는 것보다 정확하다): `LegacyEvidenceTest`(옛 근거 JSON → 중립으로 읽혀 같은 결과)와 `SignalCollectionTest.rejudgeReusesFrozenActiveSubmitters`(재판정 차액 0, 동결값 재사용).
- 감사 로그 액션 이름(정확히): `PARAM_DRAFT_UPDATE`(기존), `BACKTEST_DATASET_UPLOAD`, `PARAM_BACKTEST`.
- 시각은 UTC 저장·주입 `Clock` 사용(`Instant.now()` 금지), 화면 표시는 KST `yyyy-MM-dd HH:mm`. 지속성의 "날"은 **KST 달력일**.
- 같은 빈의 `@Transactional` 메서드를 그 빈 안에서 호출하지 않는다(self-invocation, CLAUDE.md).
- **테스트 실행.** Docker가 실행 중이어야 한다(`docker info`). JDK가 없는 이 PC에서는 저장소 루트에서(`<태스크>` 자리에 Gradle 인자, `--tests` 필터는 큰따옴표):
  ```bash
  MSYS_NO_PATHCONV=1 docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$(cygpath -w "$PWD/backend"):/src:ro" -v trd-gradle-test-cache:/root/.gradle -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal eclipse-temurin:17-jdk bash -c 'cp -r /src /workspace && cd /workspace && sed -i "s/\r$//" gradlew && ./gradlew --no-daemon <태스크>'
  ```
  JDK가 있으면 `backend/`에서 `./gradlew <태스크>`.
- **공유 테스트 DB.** 통합 테스트 클래스는 컨테이너 하나를 공유한다(`AbstractIntegrationTest`). 이름·키는 랜덤, 단언은 자기 데이터로만.
  - 활성 제보자 수를 정확히 세는 테스트는 **자기 전용 연도**를 쓴다: `SignalCollectionTest` 2033·2034·2035, `ParamApplyEndToEndTest` 2036. 다른 테스트는 이 연도를 쓰지 않는다.
  - 파라미터 드래프트는 전역 하나다. 스튜디오 테스트는 시작·끝에 `fx.clearActiveDrafts()`(Task 6)를 부르고, **적용(APPLIED)시킨 드래프트는 `finally`에서 `fx.deleteDraft`로 지운다** — 남으면 이후 모든 판정 테스트의 운영값이 바뀐다.
  - `backtest_datasets`는 DELETE가 막혀 있다(트리거). 테스트는 이름에 랜덤 문자열을 넣어 새 행을 만든다.
- 모든 `@SpringBootTest`(4곳: `AbstractIntegrationTest`, `AccountBootstrapModeTest`, `AdminAccountBootstrapTest`, `SlaLastAdminTest`)의 `properties`에 `"signal.hash-secret=test-signal-secret"`(Task 4).
- 각 태스크 끝에 `compileJava compileTestJava`와 그 태스크의 테스트가 통과해야 한다. Task 12에서 전체 `test`.

## Review Focus

사용자가 실제로 만날 가능성이 높은데 스펙의 테스트 목록이 직접 누르지 않는 입력 다섯 가지. 각 줄의 테스트는 해당 태스크에 들어 있다.

1. **한글·공백이 인코딩되지 않은 근거 링크**(`https://gall.dcinside.com/board/view/?id=새싹 챌린지`) → `java.net.URI`처럼 거부하지 않고 디시로 판별(Task 1 `hangulQueryStillResolves`).
2. **`활성 × 비율`의 부동소수 잡음**(60 × 0.1 = 6.000000000000001) → 목표치 7이 아니라 6(Task 2 `ceilIgnoresFloatNoise`).
3. **옛 앱 빌드**가 플랫폼 칩 값만 보내고 기기 헤더는 안 보냄 → 201, 플랫폼은 링크에서, `source_platform`에 칩 값 보관, 기기 해시 NULL(Task 4 `legacyClientStillSubmits`).
4. **백테스트 사례에서 같은 제보자 이름표가 두 번** 나옴 → 한 명으로 센다, 오류 아님(Task 8 `sameSubmitterTwiceCountsOnce`).
5. **SP4 배포 시점에 이미 승인 대기(REVIEW) 중인 옛 드래프트**(`submitterTarget`·`hitThreshold`만) → 승인되면 새 축은 중립으로 적용, 0이 아님(Task 6 `legacyPayloadReadsNeutral`).

---

## 파일 구조

| 파일 | 책임 | 태스크 |
|---|---|---|
| `backend/domain-core/.../signal/{Platform,PlatformResolver}.java` | 플랫폼 목록·링크 판별 | 1 |
| `backend/domain-core/.../signal/{IndependenceMode,SignalAxes,TBreakdown,Independence}.java` | 새 축 파라미터·T 분해·독립성 압축 | 2 |
| `backend/domain-core/.../verdict/{TrendSignal,VerdictEngine,VerdictOutcome}.java`, `score/{VerdictPlan,VerdictComputation}.java`, `params/ParameterSet.java` | 공식 | 2 |
| `backend/persistence/src/main/resources/db/migration/V31__sp4_verdict_signals.sql`, `V31_2__submission_platform_constraints.sql`, `backend/persistence/src/main/java/db/migration/V31_1__backfill_submission_platform.java` | 스키마 | 3 |
| `backend/persistence/.../entity/Submission.java`, `repo/SubmissionRepository.java` | 플랫폼·해시 컬럼, 활성 제보자 조회 | 3, 4, 5 |
| `backend/api-public/.../service/{SignalHasher,SubmissionOrigin,SubmissionService,TrendQueryService}.java`, `web/{SubmissionController,SubmissionCreateRequest}.java` | 수집·표시 | 4 |
| `backend/api-admin/.../seed/AdminSeedService.java`, `trend/TrendItemAdminService.java`, `web/MergeQueueController.java` | 시딩·표시 | 4, 5 |
| `backend/judge/.../{JudgeService,ParamsSnapshot,VerdictEvidence}.java` | 수집·동결·재판정 | 5 |
| `backend/api-admin/.../verdict/VerdictController.java` | T 분해 표시 | 5 |
| `backend/domain-core/.../params/DraftValues.java`, `backend/persistence/.../entity/ParameterDraft.java` | 9개 값·옛 payload 호환 | 6 |
| `backend/api-admin/.../params/ParamStudioService.java`, `web/ParamStudioController.java` | 스튜디오 | 6, 8 |
| `backend/domain-core/.../backtest/{Backtest,BacktestCase,BacktestReport}.java` | 백테스트 계산 | 7 |
| `backend/api-admin/.../params/{BacktestDatasetParser,BacktestService}.java`, `web/BacktestController.java`, `approval/ParamApplyExecutor.java` | 백테스트 API·승인 요약 | 8 |
| `backend/persistence/.../entity/BacktestDataset.java`, `repo/BacktestDatasetRepository.java` | 데이터셋 보관 | 8 |
| `backend/api-admin/src/main/resources/backtest/synthetic-v1.json` | 합성 시나리오 12건 | 8 |
| `backend/app/src/test/java/kr/trendstage/support/{Fixtures,AbstractIntegrationTest}.java` | 픽스처·테스트 속성 | 3, 4, 5, 6 |
| `backend/app/src/test/java/kr/trendstage/{signal,params}/*Test.java` | 통합 테스트 | 3~8 |
| `backend/api-spec/openapi.yaml` | 계약 | 9 |
| `admin/src/**` | 콘솔 | 10 |
| `app/src/**`, `app/package.json` | 앱 | 11 |
| `CLAUDE.md`, `01`·`02`·`04`·`05`, `README.md`, `infra/{docker-compose.yml,.env.example}`, `backend/app/src/main/resources/application.yml` | 문서·설정 | 4, 12 |

---

### Task 1: 플랫폼 목록과 링크 판별

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/signal/Platform.java`
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/signal/PlatformResolver.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/signal/PlatformResolverTest.java`

**Interfaces:**
- Produces:
  - `enum Platform { DCINSIDE, THEQOO, FMKOREA, INSTIZ, X, INSTAGRAM, THREADS, YOUTUBE, TIKTOK, NAVER, ETC }` — `label(): String`, `domains(): List<String>`, `static labelOf(String code): String`(코드가 아니면 입력 그대로, null이면 null)
  - `PlatformResolver.resolve(String url): Platform` — 절대 null을 돌려주지 않는다

- [ ] **Step 1: 실패하는 테스트 작성**

```java
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
```

`domain-core`의 `testImplementation("org.junit.jupiter:junit-jupiter")` 번들에 `@ParameterizedTest`(junit-jupiter-params)가 들어 있다 — 의존성 추가 없음.

- [ ] **Step 2: 실패 확인**

Run: `<태스크>` = `:domain-core:test --tests "kr.trendstage.domain.signal.PlatformResolverTest"`
Expected: 컴파일 실패(`Platform` 없음)

- [ ] **Step 3: 구현**

`Platform.java`:

```java
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
```

`PlatformResolver.java`:

```java
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
```

- [ ] **Step 4: 통과 확인**

Run: `<태스크>` = `:domain-core:test --tests "kr.trendstage.domain.signal.PlatformResolverTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/signal/Platform.java backend/domain-core/src/main/java/kr/trendstage/domain/signal/PlatformResolver.java backend/domain-core/src/test/java/kr/trendstage/domain/signal/PlatformResolverTest.java
git commit -m "feat(signal): 플랫폼 목록과 근거 링크 도메인 판별(SP4 S4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 판정 공식 — 새 축·T 분해·독립성 압축

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/signal/{IndependenceMode,SignalAxes,TBreakdown,Independence}.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/verdict/{TrendSignal,VerdictEngine,VerdictOutcome}.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/score/{VerdictPlan,VerdictComputation}.java`
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/params/ParameterSet.java`
- Modify(이름 변경·생성자 호출만): `submitterTarget` → `targetFloor`, `new TrendSignal(…)`·`new TrendSignal.Entry(6인자)` → 팩토리. 대상은 `grep -rn "submitterTarget\|new TrendSignal" backend --include=*.java`로 찾는다 — `judge/.../{JudgeService,ParamsSnapshot}.java`, `persistence/.../entity/ParameterDraft.java`, `api-admin/.../params/ParamStudioService.java`, `api-admin/.../web/ParamStudioController.java`, `domain-core/src/test/**`, `app/src/test/**`. **JSON 키 `submitterTarget`(드래프트 payload, `ParamsSnapshot` 컴포넌트 이름)은 바꾸지 않는다** — 옛 데이터 호환(Task 5·6이 다룬다).
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/signal/SignalAxesEngineTest.java`

**Interfaces:**
- Consumes: Task 1 `Platform`
- Produces:
  - `enum IndependenceMode { OFF, DEVICE, DEVICE_OR_IP }`
  - `record SignalAxes(double targetRatio, int activeWindowDays, double persistenceFloor, int persistenceFullDays, double diversityFloor, int diversityFullPlatforms, IndependenceMode independenceMode)` — `NEUTRAL`, 범위 위반 시 `IllegalArgumentException`(메시지 = 필드명으로 시작), `public static void unit(String, double)`, `public static void between(String, int, int, int)`
  - `record TBreakdown(int accounts, int independent, int target, Integer activeSubmitters, double ratio, int activeDays, int persistenceFullDays, double persistence, int platforms, int diversityFullPlatforms, boolean diversityApplied, double diversity, double t)` — `describe(): String`
  - `Independence.count(List<TrendSignal.Entry> nonSeed, IndependenceMode): int`
  - `record TrendSignal(Instant deadline, List<Entry> entries, Integer activeSubmitters)` — `static of(Instant, List<Entry>)`(activeSubmitters null), `validCount()`, `distinctSubmitters()`, `distinctPlatforms()`
  - `record TrendSignal.Entry(UUID submissionId, UUID userId, boolean seed, Instant submittedAt, String platform, Instant userJoinedAt, String platformCode, Integer deviceGroup, Integer ipGroup)` — `static legacy(UUID, UUID, boolean, Instant, String platform, Instant)`(새 값 null)
  - `ParameterSet`: 필드 `targetFloor`(구 `submitterTarget`), `axes: SignalAxes`. 16인자 생성자(axes = NEUTRAL) + 17인자 생성자(끝에 `SignalAxes`)
  - `VerdictEngine.breakdown(TrendSignal, ParameterSet): TBreakdown`, `evaluate(...)`, `computeT(...)`
  - `record VerdictOutcome(VerdictResult result, ReachLevel reach, double t, TBreakdown breakdown)`
  - `record VerdictPlan(VerdictResult result, ReachLevel reach, double t, List<LedgerLine> ledgerLines, TBreakdown breakdown)` — VOID면 breakdown null

**설계 메모.** `TrendSignal`·`Entry`는 판정 근거 JSON으로 직렬화·역직렬화되는 레코드다. **보조 생성자를 두지 않는다** — Jackson이 레코드의 생성자를 고를 때 모호해질 수 있다. 옛 호출은 정적 팩토리(`of`, `legacy`)로 바꾼다.

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.domain.signal;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignalAxesEngineTest {

    static final Instant DEADLINE = Instant.parse("2026-06-15T00:00:00Z");

    // ── 기본값 = 옛 공식 ────────────────────────────────────────────────────

    @Test
    void defaultsMatchLegacyFormulaBitForBit() {
        Random rnd = new Random(42);
        ParameterSet d = ParameterSet.defaults();
        List<UUID> pool = new ArrayList<>();
        for (int i = 0; i < 30; i++) pool.add(UUID.randomUUID());
        Platform[] platforms = Platform.values();
        for (int n = 0; n < 200; n++) {
            List<TrendSignal.Entry> entries = new ArrayList<>();
            int size = rnd.nextInt(41);
            for (int i = 0; i < size; i++) {
                entries.add(new TrendSignal.Entry(UUID.randomUUID(), pool.get(rnd.nextInt(pool.size())),
                        rnd.nextInt(10) == 0, DEADLINE.minus(Duration.ofMinutes(rnd.nextInt(14 * 24 * 60) + 1)),
                        null, null,
                        rnd.nextInt(5) == 0 ? null : platforms[rnd.nextInt(platforms.length)].name(),
                        rnd.nextBoolean() ? null : rnd.nextInt(4) + 1,
                        rnd.nextBoolean() ? null : rnd.nextInt(4) + 1));
            }
            Integer active = rnd.nextBoolean() ? null : rnd.nextInt(501);
            TrendSignal sig = new TrendSignal(DEADLINE, entries, active);

            double legacyT = Math.max(0.0, Math.min(1.0, sig.distinctSubmitters() / 20.0));
            VerdictOutcome o = VerdictEngine.evaluate(sig, d);
            assertEquals(legacyT, o.t(), 0.0, "signal #" + n);
            assertEquals(legacyClassify(legacyT), o.result(), "signal #" + n);
            assertEquals(legacyReach(legacyT), o.reach(), "signal #" + n);
        }
    }

    static VerdictResult legacyClassify(double t) {
        return t < 0.20 ? VerdictResult.MISS : VerdictResult.HIT;
    }

    static ReachLevel legacyReach(double t) {
        if (t < 0.20) return null;
        if (t < 0.35) return ReachLevel.L1;
        if (t < 0.55) return ReachLevel.L2;
        if (t < 0.75) return ReachLevel.L3;
        return ReachLevel.L4;
    }

    // ── 목표치(S2) ──────────────────────────────────────────────────────────

    @Test
    void targetIsFloorWhenActiveMissing() {
        TBreakdown b = VerdictEngine.breakdown(signal(users(5), null), params(20, axes(0.5, 1.0, 5, 1.0, 3, IndependenceMode.OFF)));
        assertEquals(20, b.target());
        assertNull(b.activeSubmitters());
    }

    @Test
    void targetUsesCeilOfActiveTimesRatio() {
        ParameterSet p = params(20, axes(0.5, 1.0, 5, 1.0, 3, IndependenceMode.OFF));
        assertEquals(31, VerdictEngine.breakdown(signal(users(5), 61), p).target());   // ⌈30.5⌉
        assertEquals(20, VerdictEngine.breakdown(signal(users(5), 10), p).target());   // 5 < 하한
    }

    @Test
    void ceilIgnoresFloatNoise() {   // Review Focus 2 — 60 × 0.1 = 6.000000000000001
        ParameterSet p = params(1, axes(0.1, 1.0, 5, 1.0, 3, IndependenceMode.OFF));
        assertEquals(6, VerdictEngine.breakdown(signal(users(5), 60), p).target());
        assertEquals(40, VerdictEngine.breakdown(signal(users(5), 400), p).target());
    }

    // ── 지속성(S3) ──────────────────────────────────────────────────────────

    @Test
    void persistenceCountsKstCalendarDays() {
        ParameterSet p = params(20, axes(0.0, 0.5, 4, 1.0, 3, IndependenceMode.OFF));
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), s = UUID.randomUUID();
        TrendSignal twoDays = new TrendSignal(DEADLINE, List.of(
                entry(a, false, Instant.parse("2026-06-01T14:59:59Z"), "X", null, null),   // KST 6/1 23:59:59
                entry(b, false, Instant.parse("2026-06-01T15:00:00Z"), "X", null, null),   // KST 6/2 00:00
                entry(s, true, Instant.parse("2026-06-05T03:00:00Z"), "X", null, null)),   // 시딩 날짜는 안 셈
                null);
        TBreakdown two = VerdictEngine.breakdown(twoDays, p);
        assertEquals(2, two.activeDays());
        assertEquals(0.75, two.persistence(), 1e-12);

        TrendSignal oneDay = new TrendSignal(DEADLINE, List.of(
                entry(a, false, Instant.parse("2026-06-01T00:00:00Z"), "X", null, null),
                entry(b, false, Instant.parse("2026-06-01T14:59:00Z"), "X", null, null)), null);
        assertEquals(1, VerdictEngine.breakdown(oneDay, p).activeDays());
        assertEquals(0.625, VerdictEngine.breakdown(oneDay, p).persistence(), 1e-12);
    }

    @Test
    void persistenceCapsAtOne() {
        ParameterSet p = params(20, axes(0.0, 0.5, 4, 1.0, 3, IndependenceMode.OFF));
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int d = 0; d < 6; d++) es.add(entry(UUID.randomUUID(), false, Instant.parse("2026-06-01T03:00:00Z").plus(Duration.ofDays(d)), "X", null, null));
        assertEquals(1.0, VerdictEngine.breakdown(new TrendSignal(DEADLINE, es, null), p).persistence(), 0.0);
    }

    // ── 다양성(S4) ──────────────────────────────────────────────────────────

    @Test
    void diversityFromPlatformCodes() {
        ParameterSet p = params(20, axes(0.0, 1.0, 5, 0.5, 3, IndependenceMode.OFF));
        assertEquals(0.5, VerdictEngine.breakdown(codes("DCINSIDE", "DCINSIDE"), p).diversity(), 1e-12);
        assertEquals(0.75, VerdictEngine.breakdown(codes("DCINSIDE", "X"), p).diversity(), 1e-12);
        assertEquals(1.0, VerdictEngine.breakdown(codes("DCINSIDE", "X", "INSTAGRAM", "YOUTUBE"), p).diversity(), 0.0);
        TBreakdown etc = VerdictEngine.breakdown(codes("ETC", "ETC", "DCINSIDE"), p);   // 기타는 1종
        assertEquals(2, etc.platforms());
    }

    @Test
    void diversityIsNeutralWhenAnyCodeMissing() {   // S8 — SP4 이전 제보가 섞이면 판별하지 않는다
        ParameterSet p = params(20, axes(0.0, 1.0, 5, 0.5, 3, IndependenceMode.OFF));
        TBreakdown b = VerdictEngine.breakdown(codes("DCINSIDE", null), p);
        assertEquals(1.0, b.diversity(), 0.0);
        assertFalse(b.diversityApplied());
    }

    // ── 독립성(S5) ──────────────────────────────────────────────────────────

    @Test
    void deviceModeMergesSharedDevice() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<TrendSignal.Entry> es = List.of(e(a, 1, null), e(b, 1, null), e(c, 2, null));
        assertEquals(2, Independence.count(es, IndependenceMode.DEVICE));
        assertEquals(3, Independence.count(es, IndependenceMode.OFF));
    }

    @Test
    void deviceOrIpIsTransitive() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        List<TrendSignal.Entry> es = List.of(e(a, 1, 5), e(b, 1, 7), e(c, 3, 7));   // a–b 기기, b–c IP
        assertEquals(1, Independence.count(es, IndependenceMode.DEVICE_OR_IP));
        assertEquals(2, Independence.count(es, IndependenceMode.DEVICE));
    }

    @Test
    void nullGroupsNeverLink() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertEquals(2, Independence.count(List.of(e(a, null, null), e(b, null, null)), IndependenceMode.DEVICE_OR_IP));
    }

    @Test
    void sameUserManyEntriesCountsOnceAndSeedsAreExcluded() {
        UUID a = UUID.randomUUID(), seed = UUID.randomUUID();
        TrendSignal sig = new TrendSignal(DEADLINE, List.of(
                entry(a, false, DEADLINE.minusSeconds(10), "X", 1, null),
                entry(a, false, DEADLINE.minusSeconds(5), "X", 2, null),
                entry(seed, true, DEADLINE.minusSeconds(3), "X", 1, null)), null);
        TBreakdown b = VerdictEngine.breakdown(sig, params(20, axes(0.0, 1.0, 5, 1.0, 3, IndependenceMode.DEVICE)));
        assertEquals(1, b.accounts());
        assertEquals(1, b.independent());
    }

    // ── 범위 검증 ───────────────────────────────────────────────────────────

    @Test
    void axesValidateRangesWithFieldNameFirst() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.5, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("persistenceFloor"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.0, 5, 1.0, 1, IndependenceMode.OFF))
                .getMessage().startsWith("diversityFullPlatforms"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new SignalAxes(0.0, 6, 1.0, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("activeWindowDays"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(Double.NaN, 1.0, 5, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("targetRatio"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> axes(0.0, 1.0, 15, 1.0, 3, IndependenceMode.OFF))
                .getMessage().startsWith("persistenceFullDays"));
    }

    // ── 산정 근거 문자열(§2.3) ─────────────────────────────────────────────

    @Test
    void describeFormat() {
        TBreakdown plain = new TBreakdown(15, 15, 20, 150, 0.75, 2, 5, 0.80, 2, 3, true, 0.80, 0.48);
        assertEquals("T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일/5일) × 다양성 0.80 (2곳/3곳)", plain.describe());
        TBreakdown merged = new TBreakdown(15, 13, 20, null, 0.65, 3, 5, 1.0, 1, 3, false, 1.0, 0.65);
        assertEquals("T 0.65 = 제보자 13/20 (0.65 · 계정 15 → 독립 13) × 지속성 1.00 (3일/5일) × 다양성 1.00 (플랫폼 판별 없음)",
                merged.describe());
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────

    static SignalAxes axes(double ratio, double pFloor, int pFull, double dFloor, int dFull, IndependenceMode mode) {
        return new SignalAxes(ratio, 28, pFloor, pFull, dFloor, dFull, mode);
    }

    static ParameterSet params(int floor, SignalAxes axes) {
        ParameterSet d = ParameterSet.defaults();
        return new ParameterSet(floor, d.hitThreshold, d.bandL2, d.bandL3, d.bandL4, d.mL1, d.mL2, d.mL3, d.mL4,
                d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta, axes);
    }

    static TrendSignal.Entry entry(UUID user, boolean seed, Instant at, String code, Integer device, Integer ip) {
        return new TrendSignal.Entry(UUID.randomUUID(), user, seed, at, null, null, code, device, ip);
    }

    static TrendSignal.Entry e(UUID user, Integer device, Integer ip) {
        return entry(user, false, DEADLINE.minusSeconds(60), "X", device, ip);
    }

    static TrendSignal signal(List<TrendSignal.Entry> es, Integer active) {
        return new TrendSignal(DEADLINE, es, active);
    }

    static List<TrendSignal.Entry> users(int n) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int i = 0; i < n; i++) es.add(e(UUID.randomUUID(), null, null));
        return es;
    }

    static TrendSignal codes(String... codes) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (String c : codes) es.add(entry(UUID.randomUUID(), false, DEADLINE.minusSeconds(60), c, null, null));
        return new TrendSignal(DEADLINE, es, null);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `<태스크>` = `:domain-core:test --tests "kr.trendstage.domain.signal.SignalAxesEngineTest"`
Expected: 컴파일 실패(`SignalAxes`·`TBreakdown` 없음)

- [ ] **Step 3: 새 타입 구현**

`IndependenceMode.java`:

```java
package kr.trendstage.domain.signal;

/**
 * 제보자 독립성 압축 모드(SP4 S5). 기본 OFF.
 * DEVICE_OR_IP는 통신사 NAT로 무관한 유저가 같은 IP를 쓰는 오탐 위험이 있다 — 실사례 백테스트 전엔 켜지 않는다.
 */
public enum IndependenceMode { OFF, DEVICE, DEVICE_OR_IP }
```

`SignalAxes.java`:

```java
package kr.trendstage.domain.signal;

/**
 * SP4가 더한 판정 축의 파라미터(스펙 §5.1). NEUTRAL이면 판정이 SP4 이전과 같다(S6).
 * 범위를 벗어나면 IllegalArgumentException — 메시지는 필드명으로 시작한다(스튜디오가 그대로 보여 준다).
 */
public record SignalAxes(double targetRatio, int activeWindowDays,
                         double persistenceFloor, int persistenceFullDays,
                         double diversityFloor, int diversityFullPlatforms,
                         IndependenceMode independenceMode) {

    public static final SignalAxes NEUTRAL = new SignalAxes(0.0, 28, 1.0, 5, 1.0, 3, IndependenceMode.OFF);

    public SignalAxes {
        unit("targetRatio", targetRatio);
        between("activeWindowDays", activeWindowDays, 7, 90);
        unit("persistenceFloor", persistenceFloor);
        between("persistenceFullDays", persistenceFullDays, 1, 14);
        unit("diversityFloor", diversityFloor);
        between("diversityFullPlatforms", diversityFullPlatforms, 2, Platform.values().length);
        if (independenceMode == null) throw new IllegalArgumentException("independenceMode: 값이 필요합니다");
    }

    /** 0 ~ 1(NaN 거부). */
    public static void unit(String name, double v) {
        if (!(v >= 0.0 && v <= 1.0)) {
            throw new IllegalArgumentException(name + ": 0 ~ 1 사이여야 합니다 (입력 " + v + ")");
        }
    }

    public static void between(String name, int v, int min, int max) {
        if (v < min || v > max) {
            throw new IllegalArgumentException(name + ": " + min + " ~ " + max + " 사이여야 합니다 (입력 " + v + ")");
        }
    }
}
```

`TBreakdown.java`:

```java
package kr.trendstage.domain.signal;

import java.util.Locale;

/**
 * 유효 T의 분해(스펙 §2). 판정 근거·ADM-111/200·스튜디오·백테스트가 같은 값을 표시한다.
 * activeSubmitters가 null이면 SP4 이전 판정이거나 백테스트 사례에 값이 없는 것 — 목표치는 하한이다.
 * diversityApplied가 false면 플랫폼 코드가 없는 제보(SP4 이전)가 섞여 다양성을 1로 둔 것이다(S8).
 */
public record TBreakdown(int accounts, int independent, int target, Integer activeSubmitters, double ratio,
                         int activeDays, int persistenceFullDays, double persistence,
                         int platforms, int diversityFullPlatforms, boolean diversityApplied, double diversity,
                         double t) {

    /** 예: "T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일/5일) × 다양성 0.80 (2곳/3곳)" */
    public String describe() {
        String who = accounts == independent
                ? "제보자 %d/%d (%s)".formatted(independent, target, f(ratio))
                : "제보자 %d/%d (%s · 계정 %d → 독립 %d)".formatted(independent, target, f(ratio), accounts, independent);
        String div = diversityApplied
                ? "다양성 %s (%d곳/%d곳)".formatted(f(diversity), platforms, diversityFullPlatforms)
                : "다양성 %s (플랫폼 판별 없음)".formatted(f(diversity));
        return "T %s = %s × 지속성 %s (%d일/%d일) × %s".formatted(
                f(t), who, f(persistence), activeDays, persistenceFullDays, div);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
```

`Independence.java`:

```java
package kr.trendstage.domain.signal;

import kr.trendstage.domain.verdict.TrendSignal;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 독립 제보자 수(SP4 S5). 같은 기기(모드 DEVICE·DEVICE_OR_IP) 또는 같은 IP(DEVICE_OR_IP) 그룹을 공유하는
 * 제보자를 전이적으로 묶는다(union-find). 그룹이 null인 제보는 잇지 않는다. 시딩은 호출 측이 빼고 넘긴다.
 */
public final class Independence {
    private Independence() {}

    public static int count(List<TrendSignal.Entry> nonSeed, IndependenceMode mode) {
        List<UUID> users = nonSeed.stream().map(TrendSignal.Entry::userId).distinct().toList();
        if (mode == IndependenceMode.OFF) return users.size();

        Map<UUID, UUID> parent = new HashMap<>();
        users.forEach(u -> parent.put(u, u));
        Map<Integer, UUID> byDevice = new HashMap<>();
        Map<Integer, UUID> byIp = new HashMap<>();
        for (TrendSignal.Entry e : nonSeed) {
            if (e.deviceGroup() != null) {
                UUID first = byDevice.putIfAbsent(e.deviceGroup(), e.userId());
                if (first != null) union(parent, first, e.userId());
            }
            if (mode == IndependenceMode.DEVICE_OR_IP && e.ipGroup() != null) {
                UUID first = byIp.putIfAbsent(e.ipGroup(), e.userId());
                if (first != null) union(parent, first, e.userId());
            }
        }
        return (int) users.stream().map(u -> find(parent, u)).distinct().count();
    }

    private static UUID find(Map<UUID, UUID> parent, UUID u) {
        UUID root = u;
        while (!parent.get(root).equals(root)) root = parent.get(root);
        return root;
    }

    private static void union(Map<UUID, UUID> parent, UUID a, UUID b) {
        UUID ra = find(parent, a), rb = find(parent, b);
        if (!ra.equals(rb)) parent.put(ra, rb);
    }
}
```

- [ ] **Step 4: `TrendSignal` 교체**

```java
package kr.trendstage.domain.verdict;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 판정 입력 = 관측 마감 전에 들어온 비VOID 제보 전부(J6). 외부 지표 없음(R5).
 * 시딩도 담는다 — "유효 제보 0건" 판정과 시딩 결과 기록에 필요하고, 계산이 시딩을 뺀다(P2, J3).
 * SP4: activeSubmitters(상대 목표치 입력, 판정 시점 동결)와 제보별 플랫폼 코드·기기/IP 그룹 번호.
 * 판정 근거(evidence_json)에 그대로 동결된다 — 해시는 담지 않는다(S7). SP4 이전 근거에는 새 값이 없다(null, S8).
 * JSON으로 오가는 레코드라 보조 생성자를 두지 않는다 — 옛 형식은 정적 팩토리로 만든다.
 */
public record TrendSignal(Instant deadline, List<Entry> entries, Integer activeSubmitters) {

    /**
     * @param platform     SP4 이전 자유 텍스트(옛 근거 호환용). 새 판정에서는 null
     * @param platformCode {@link kr.trendstage.domain.signal.Platform} 이름. SP4 이전 근거에는 없다
     * @param deviceGroup  이 판정 안에서만 의미 있는 기기 그룹 번호(1부터). 기기 정보가 없으면 null
     * @param ipGroup      같은 방식의 IP 그룹 번호
     */
    public record Entry(UUID submissionId, UUID userId, boolean seed, Instant submittedAt, String platform,
                        Instant userJoinedAt, String platformCode, Integer deviceGroup, Integer ipGroup) {

        /** SP4 이전 형식(플랫폼 자유 텍스트만). */
        public static Entry legacy(UUID submissionId, UUID userId, boolean seed, Instant submittedAt,
                                   String platform, Instant userJoinedAt) {
            return new Entry(submissionId, userId, seed, submittedAt, platform, userJoinedAt, null, null, null);
        }
    }

    public TrendSignal {
        entries = List.copyOf(entries);
    }

    /** activeSubmitters 없이 — 목표치는 하한이 된다. */
    public static TrendSignal of(Instant deadline, List<Entry> entries) {
        return new TrendSignal(deadline, entries, null);
    }

    /** 유효(비VOID) 제보 수 — 시딩 포함. 0이면 판정은 VOID(P5). */
    public int validCount() {
        return entries.size();
    }

    /** 시딩을 뺀 서로 다른 제보자(계정) 수 — 압축 전. */
    public int distinctSubmitters() {
        return (int) entries.stream().filter(e -> !e.seed()).map(Entry::userId).distinct().count();
    }

    /** 시딩을 뺀 서로 다른 플랫폼 수 — 코드가 있으면 코드, 없으면(SP4 이전) 자유 텍스트로 센다. 표시용. */
    public int distinctPlatforms() {
        return (int) entries.stream().filter(e -> !e.seed())
                .map(e -> e.platformCode() != null ? e.platformCode() : e.platform())
                .filter(Objects::nonNull).distinct().count();
    }
}
```

- [ ] **Step 5: `ParameterSet` — 이름 변경 + 축**

필드 `submitterTarget`를 `targetFloor`로 바꾸고(주석: `// 목표치 하한(구 submitterTarget) — 목표치 = max(하한, ⌈활성 제보자 × 비율⌉) (SP4 S2)`), 필드 `public final SignalAxes axes;`를 추가한다. 생성자는 두 개:

```java
    /** SP4 이전 16인자 — 새 축은 중립(S6·S8). */
    public ParameterSet(int targetFloor, double hitThreshold, double bandL2, double bandL3, double bandL4,
                        double mL1, double mL2, double mL3, double mL4,
                        double wRank1, double wRank2, double wRank3, double wRankRest,
                        int halflifeDays, double tiAlpha, double tiBeta) {
        this(targetFloor, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta, SignalAxes.NEUTRAL);
    }

    public ParameterSet(int targetFloor, double hitThreshold, double bandL2, double bandL3, double bandL4,
                        double mL1, double mL2, double mL3, double mL4,
                        double wRank1, double wRank2, double wRank3, double wRankRest,
                        int halflifeDays, double tiAlpha, double tiBeta, SignalAxes axes) {
        if (targetFloor < 1) throw new IllegalArgumentException("targetFloor: 1 이상이어야 합니다 (입력 " + targetFloor + ")");
        this.targetFloor = targetFloor;
        this.hitThreshold = hitThreshold; this.bandL2 = bandL2; this.bandL3 = bandL3; this.bandL4 = bandL4;
        this.mL1 = mL1; this.mL2 = mL2; this.mL3 = mL3; this.mL4 = mL4;
        this.wRank1 = wRank1; this.wRank2 = wRank2; this.wRank3 = wRank3; this.wRankRest = wRankRest;
        this.halflifeDays = halflifeDays; this.tiAlpha = tiAlpha; this.tiBeta = tiBeta;
        this.axes = java.util.Objects.requireNonNull(axes, "axes");
    }
```

`defaults()`는 그대로(16인자 → NEUTRAL). 클래스 Javadoc 끝에 "SP4: 목표치 하한·비율과 지속성·다양성·독립성 축(`axes`). 기본은 중립 — 판정이 SP4 이전과 같다." 한 줄을 더한다.

- [ ] **Step 6: `VerdictOutcome`·`VerdictEngine`·`VerdictPlan`·`VerdictComputation`**

`VerdictOutcome.java`:

```java
package kr.trendstage.domain.verdict;

import kr.trendstage.domain.signal.TBreakdown;

/** 판정 결과 + 유효 T + 확산규모(HIT만) + T 분해. */
public record VerdictOutcome(VerdictResult result, ReachLevel reach, double t, TBreakdown breakdown) {}
```

`VerdictEngine.java` 전체:

```java
package kr.trendstage.domain.verdict;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.Independence;
import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.domain.signal.TBreakdown;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 유효 T와 판정 결과를 산출하는 순수 함수(SP4 §2).
 *
 * <pre>
 *   independent = 독립 제보자 수(시딩 제외, independenceMode로 압축)
 *   target      = max(targetFloor, ⌈activeSubmitters × targetRatio⌉)   — activeSubmitters 없으면 targetFloor
 *   ratio       = clip(independent / target, 0, 1)
 *   persistence = pFloor + (1 − pFloor) × min(1, 제보일수(KST) / pFullDays)
 *   diversity   = dFloor + (1 − dFloor) × min(1, (플랫폼 수 − 1) / (dFullPlatforms − 1))   — 코드 없는 제보가 섞이면 1
 *   T = ratio × persistence × diversity
 *   T < hitThreshold → MISS, 이후 bandL2/L3/L4로 L1~L4
 * </pre>
 *
 * 기본값(SignalAxes.NEUTRAL)에서는 persistence = diversity = 1.0, target = targetFloor라 SP4 이전 공식과 같다(S6).
 * 값은 근거 없는 초기 추정치다 — 백테스트(ADM-600)를 거쳐 2인 승인으로 바꾼다(O1·O8).
 * 시딩은 T에서 빠진다(J3). VOID는 엔진 밖의 사건이고, 판정 시점 VOID는 유효 제보 0건뿐이다(P5).
 */
public final class VerdictEngine {
    private VerdictEngine() {}

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** ⌈활성 × 비율⌉의 부동소수 잡음 제거(60 × 0.1 = 6.000000000000001). */
    private static final double CEIL_EPSILON = 1e-9;

    public static double computeT(TrendSignal sig, ParameterSet p) {
        return breakdown(sig, p).t();
    }

    public static VerdictOutcome evaluate(TrendSignal sig, ParameterSet p) {
        return classify(breakdown(sig, p), p);
    }

    public static TBreakdown breakdown(TrendSignal sig, ParameterSet p) {
        SignalAxes a = p.axes;
        List<TrendSignal.Entry> real = sig.entries().stream().filter(e -> !e.seed()).toList();

        int accounts = (int) real.stream().map(TrendSignal.Entry::userId).distinct().count();
        int independent = Independence.count(real, a.independenceMode());
        Integer active = sig.activeSubmitters();
        int target = active == null ? p.targetFloor
                : Math.max(p.targetFloor, (int) Math.ceil(active * a.targetRatio() - CEIL_EPSILON));
        double ratio = Math.max(0.0, Math.min(1.0, independent / (double) target));

        int activeDays = (int) real.stream().map(e -> LocalDate.ofInstant(e.submittedAt(), KST)).distinct().count();
        double persistence = a.persistenceFloor()
                + (1 - a.persistenceFloor()) * Math.min(1.0, activeDays / (double) a.persistenceFullDays());

        boolean coded = !real.isEmpty() && real.stream().allMatch(e -> e.platformCode() != null);
        int platforms = coded ? (int) real.stream().map(TrendSignal.Entry::platformCode).distinct().count() : 0;
        double diversity = coded
                ? a.diversityFloor() + (1 - a.diversityFloor())
                        * Math.min(1.0, (platforms - 1) / (double) (a.diversityFullPlatforms() - 1))
                : 1.0;

        double t = ratio * persistence * diversity;
        return new TBreakdown(accounts, independent, target, active, ratio, activeDays, a.persistenceFullDays(),
                persistence, platforms, a.diversityFullPlatforms(), coded, diversity, t);
    }

    private static VerdictOutcome classify(TBreakdown b, ParameterSet p) {
        double t = b.t();
        if (t < p.hitThreshold) return new VerdictOutcome(VerdictResult.MISS, null, t, b);
        ReachLevel reach;
        if (t < p.bandL2) reach = ReachLevel.L1;
        else if (t < p.bandL3) reach = ReachLevel.L2;
        else if (t < p.bandL4) reach = ReachLevel.L3;
        else reach = ReachLevel.L4;
        return new VerdictOutcome(VerdictResult.HIT, reach, t, b);
    }
}
```

`VerdictPlan.java`: 컴포넌트 끝에 `TBreakdown breakdown`을 추가하고 Javadoc에 "breakdown은 VOID면 null"을 적는다.

`VerdictComputation.run`:

```java
        if (signal.validCount() == 0) {
            return new VerdictPlan(VerdictResult.VOID, null, 0.0, List.of(), null);
        }
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new VerdictPlan(o.result(), o.reach(), o.t(), ledgerLines(o, subs, p), o.breakdown());
```

- [ ] **Step 7: 호출부 이름 변경**

`grep -rn "submitterTarget\|new TrendSignal\|new VerdictOutcome\|new VerdictPlan" backend --include=*.java`로 나온 **Java 필드 접근·생성자 호출만** 고친다.
- `p.submitterTarget`·`current.submitterTarget` → `.targetFloor`
- `new TrendSignal(deadline, entries)` → `TrendSignal.of(deadline, entries)`
- `new TrendSignal.Entry(6인자)` → `TrendSignal.Entry.legacy(6인자)`(`JudgeService.collect`는 Task 5가 다시 쓴다 — 지금은 `legacy`로 컴파일만 맞춘다)
- `ParamsSnapshot.of`: `p.submitterTarget` → `p.targetFloor`(**레코드 컴포넌트 이름 `submitterTarget`은 그대로**), `toParameterSet()`는 첫 인자를 그대로 넘긴다
- `ParameterDraft.toParameterSet`·`ParamStudioService`·`ParamStudioController`의 `ParameterSet` 필드 접근(JSON 키 문자열 `"submitterTarget"`은 그대로 — Task 6이 바꾼다)
- 테스트의 `new VerdictOutcome(3인자)`가 있으면 끝에 `null`

`TrendSignalTest.entry`는 `TrendSignal.Entry.legacy(...)`로, `new TrendSignal(T, List.of(...))`는 `TrendSignal.of(T, List.of(...))`로 바꾼다 — 단언은 그대로 통과해야 한다(`distinctPlatforms`가 코드 없을 때 자유 텍스트로 센다).

- [ ] **Step 8: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :domain-core:test`
Expected: PASS(기존 `EngineGoldenTest`·`ParamSimulationTest`·`VerdictComputationTest`·`TrendSignalTest` 포함)

- [ ] **Step 9: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/signal/IndependenceMode.java backend/domain-core/src/main/java/kr/trendstage/domain/signal/SignalAxes.java backend/domain-core/src/main/java/kr/trendstage/domain/signal/TBreakdown.java backend/domain-core/src/main/java/kr/trendstage/domain/signal/Independence.java backend/domain-core/src/main/java/kr/trendstage/domain/verdict/TrendSignal.java backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictEngine.java backend/domain-core/src/main/java/kr/trendstage/domain/verdict/VerdictOutcome.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictPlan.java backend/domain-core/src/main/java/kr/trendstage/domain/score/VerdictComputation.java backend/domain-core/src/main/java/kr/trendstage/domain/params/ParameterSet.java backend/domain-core/src/test/java/kr/trendstage/domain/signal/SignalAxesEngineTest.java
# + Step 7에서 고친 파일을 하나씩 add (git status로 확인)
git commit -m "feat(verdict): 유효 T = 제보자 비율 × 지속성 × 다양성, 상대 목표치·독립성 압축 — 기본값은 옛 공식과 같음(SP4 S1~S6)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 3: V31 스키마 + `Submission` 매핑

**Files:**
- Create: `backend/persistence/src/main/resources/db/migration/V31__sp4_verdict_signals.sql`
- Create: `backend/persistence/src/main/java/db/migration/V31_1__backfill_submission_platform.java`
- Create: `backend/persistence/src/main/resources/db/migration/V31_2__submission_platform_constraints.sql`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java`
- Modify: `backend/persistence/src/main/resources/db/seed/app_flow_test_data.sql`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/signal/SchemaV31Test.java`

**Interfaces:**
- Consumes: Task 1 `PlatformResolver.resolve`
- Produces:
  - 컬럼 `submissions.platform VARCHAR(20) NOT NULL CHECK(11개)`, `device_hash`·`ip_hash VARCHAR(64) NULL CHECK(소문자 hex 64)`, `source_platform` NULL 허용
  - 표 `backtest_datasets`(불변 트리거), 컬럼 `parameter_drafts.backtest_result JSONB`
  - `Submission#getPlatform()`, `#getDeviceHash()`, `#getIpHash()`, `#recordOrigin(String deviceHash, String ipHash)` — 생성자가 `evidenceUrl`로 `platform`을 채운다
  - `V31_1__backfill_submission_platform.backfill(Connection)`(public static)
  - `Fixtures#platform(UUID submissionId): String`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.signal;

import db.migration.V31_1__backfill_submission_platform;
import kr.trendstage.domain.signal.Platform;
import kr.trendstage.persistence.entity.Submission;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V31: 플랫폼 코드·해시 컬럼·백테스트 데이터셋(SP4 §7). */
class SchemaV31Test extends AbstractIntegrationTest {

    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager txManager;

    private UUID anySubmission() {
        Instant t = Instant.parse("2026-09-01T00:00:00Z");
        return fx.submission(fx.user(), fx.item(t), 30, t.plusSeconds(60));
    }

    @Test
    void platformCheckAcceptsEveryCodeAndRejectsOthers() {
        UUID sub = anySubmission();
        for (Platform p : Platform.values()) {
            jdbc.update("UPDATE submissions SET platform = ? WHERE id = ?", p.name(), sub);
        }
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET platform = 'MYSPACE' WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET platform = NULL WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hashColumnsAcceptOnlyLowercaseHex64() {
        UUID sub = anySubmission();
        jdbc.update("UPDATE submissions SET device_hash = ?, ip_hash = ? WHERE id = ?", "a".repeat(64), "0".repeat(64), sub);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET device_hash = 'raw-device-id' WHERE id = ?", sub))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE submissions SET ip_hash = ? WHERE id = ?", "A".repeat(64), sub))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sourcePlatformIsOptional() {
        UUID sub = anySubmission();
        jdbc.update("UPDATE submissions SET source_platform = NULL WHERE id = ?", sub);
        assertThat(jdbc.queryForObject("SELECT source_platform FROM submissions WHERE id = ?", String.class, sub)).isNull();
    }

    @Test
    void backfillResolvesFromEvidenceUrl() {
        // 제약을 잠시 풀고 NULL 행을 만든 뒤 백필을 돌리고, 트랜잭션을 롤백해 스키마를 되돌린다(PG DDL은 트랜잭션 안).
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE submissions DROP CONSTRAINT submission_platform_known");
            jdbc.execute("ALTER TABLE submissions ALTER COLUMN platform DROP NOT NULL");
            UUID a = anySubmission(), b = anySubmission(), c = anySubmission();
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://gall.dcinside.com/board/1", a);
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://youtu.be/abc", b);
            jdbc.update("UPDATE submissions SET platform = NULL, evidence_url = ? WHERE id = ?", "https://blog.example.com/p", c);
            try {
                V31_1__backfill_submission_platform.backfill(DataSourceUtils.getConnection(dataSource));
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
            assertThat(fx.platform(a)).isEqualTo("DCINSIDE");
            assertThat(fx.platform(b)).isEqualTo("YOUTUBE");
            assertThat(fx.platform(c)).isEqualTo("ETC");
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'submission_platform_known'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void backtestDatasetsAreImmutable() {
        UUID id = jdbc.queryForObject("INSERT INTO backtest_datasets (name, sha256, case_count, payload, uploaded_by) "
                + "VALUES ('불변', ?, 1, '{}'::jsonb, ?) RETURNING id", UUID.class,
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""), fx.admin());
        assertThatThrownBy(() -> jdbc.update("UPDATE backtest_datasets SET name = 'x' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM backtest_datasets WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void entityDerivesPlatformFromEvidenceUrl() {
        Submission s = new Submission(UUID.randomUUID(), UUID.randomUUID(), "새싹", "새싹", (short) 30,
                "인스타", "https://youtu.be/abc", "설명", false, false);
        assertThat(s.getPlatform()).isEqualTo("YOUTUBE");
        assertThat(s.getSourcePlatform()).isEqualTo("인스타");
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `<태스크>` = `:app:test --tests "kr.trendstage.signal.SchemaV31Test"`
Expected: 컴파일 실패(`V31_1__backfill_submission_platform`·`getPlatform` 없음)

- [ ] **Step 3: 마이그레이션 작성**

`V31__sp4_verdict_signals.sql`:

```sql
-- SP4 판정 신호 재설계(스펙 2026-09-24 §7). 기존 행을 채우는 방식 — 개발 DB 초기화 불필요.

-- 플랫폼 코드(근거 링크로 판별, S4). V31_1이 기존 행을 채우고 V31_2가 NOT NULL·CHECK를 건다.
ALTER TABLE submissions ADD COLUMN platform VARCHAR(20);

-- 기기·IP 해시(HMAC, 원문 미보관, §4). 옛 제보·헤더 없는 요청은 NULL.
ALTER TABLE submissions ADD COLUMN device_hash VARCHAR(64)
    CONSTRAINT submission_device_hash_hex CHECK (device_hash ~ '^[0-9a-f]{64}$');
ALTER TABLE submissions ADD COLUMN ip_hash VARCHAR(64)
    CONSTRAINT submission_ip_hash_hex CHECK (ip_hash ~ '^[0-9a-f]{64}$');

-- 유저가 고른 칩 값은 보관용(S12). 새 앱은 보내지 않는다.
ALTER TABLE submissions ALTER COLUMN source_platform DROP NOT NULL;

-- 상대 목표치의 활성 제보자 수(S2) — 판정마다 창(기본 28일) 범위 조회.
CREATE INDEX submission_active_window ON submissions (created_at) WHERE result <> 'VOID' AND NOT is_seed;

-- 백테스트 사례 파일(S11). 불변 — 승인자·감사자가 "어떤 데이터로 돌린 결과인가"를 확인한다.
CREATE TABLE backtest_datasets (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(120) NOT NULL,
    sha256      VARCHAR(64)  NOT NULL CONSTRAINT backtest_dataset_sha_unique UNIQUE
                             CONSTRAINT backtest_dataset_sha_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    case_count  INT          NOT NULL CONSTRAINT backtest_dataset_case_count CHECK (case_count BETWEEN 1 AND 500),
    payload     JSONB        NOT NULL,
    uploaded_by UUID         NOT NULL REFERENCES admin_accounts (id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE TRIGGER backtest_datasets_immutable
    BEFORE UPDATE OR DELETE ON backtest_datasets
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- 드래프트의 마지막 백테스트 결과(승인 요청 조건, S9). 드래프트를 고치면 지운다.
ALTER TABLE parameter_drafts ADD COLUMN backtest_result JSONB;

COMMENT ON COLUMN submissions.platform IS '근거 링크 도메인으로 판별한 플랫폼 코드(SP4 S4). 판정 다양성 축·표시에 쓴다.';
COMMENT ON COLUMN submissions.source_platform IS '유저가 고른 최초 목격 플랫폼 칩(자유 텍스트). SP4부터 보관용 — 판정·표시에 쓰지 않는다.';
COMMENT ON COLUMN submissions.device_hash IS 'HMAC-SHA256(서버 비밀값, 기기 ID). 원문은 저장하지 않는다(SP4 §4).';
COMMENT ON COLUMN submissions.ip_hash IS 'HMAC-SHA256(서버 비밀값, IPv4 전체 또는 IPv6 /64). 원문은 저장하지 않는다(SP4 §4).';
COMMENT ON TABLE backtest_datasets IS 'ADM-600 백테스트 사례 파일. 불변(트리거). 정답 라벨은 평가용이지 판정 입력이 아니다(SP4 S10).';
COMMENT ON COLUMN parameter_drafts.backtest_result IS '드래프트의 마지막 백테스트 결과(데이터셋 해시 포함). 승인 요청 조건(SP4 S9).';
```

`V31_1__backfill_submission_platform.java`(`persistence` 모듈, 패키지 `db.migration` — Flyway가 `classpath:db/migration`에서 SQL과 함께 찾는다):

```java
package db.migration;

import kr.trendstage.domain.signal.PlatformResolver;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 기존 제보의 platform을 근거 링크로 채운다(SP4 §7). SQL로 따로 짜지 않는 이유 — 판별 규칙이
 * SQL과 Java 두 벌이 되면 어긋난다. 운영 경로(Submission 생성자)와 같은 PlatformResolver를 쓴다.
 */
public class V31_1__backfill_submission_platform extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        backfill(context.getConnection());
    }

    /** 테스트가 직접 부를 수 있게 분리. 커넥션을 닫거나 커밋하지 않는다(호출 측 트랜잭션). */
    public static void backfill(Connection connection) throws SQLException {
        try (Statement select = connection.createStatement();
             ResultSet rs = select.executeQuery("SELECT id, evidence_url FROM submissions WHERE platform IS NULL");
             PreparedStatement update = connection.prepareStatement("UPDATE submissions SET platform = ? WHERE id = ?")) {
            while (rs.next()) {
                update.setString(1, PlatformResolver.resolve(rs.getString(2)).name());
                update.setObject(2, rs.getObject(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }
}
```

`persistence` 모듈에 `flyway-core`가 `implementation`으로 이미 있다(`BaseJavaMigration` 사용 가능).

`V31_2__submission_platform_constraints.sql`:

```sql
-- V31_1이 채운 platform을 필수로. 목록은 kr.trendstage.domain.signal.Platform과 같아야 한다(SchemaV31Test가 확인).
ALTER TABLE submissions ALTER COLUMN platform SET NOT NULL;
ALTER TABLE submissions ADD CONSTRAINT submission_platform_known CHECK (platform IN
    ('DCINSIDE', 'THEQOO', 'FMKOREA', 'INSTIZ', 'X', 'INSTAGRAM', 'THREADS', 'YOUTUBE', 'TIKTOK', 'NAVER', 'ETC'));
```

- [ ] **Step 4: `Submission` 엔티티**

`sourcePlatform` 매핑을 `@Column(name = "source_platform", length = 60)`(nullable)로 바꾸고 주석을 "유저가 고른 칩 값 — SP4부터 보관용(S12). 판정·표시에 쓰지 않는다"로 고친다. 아래 필드를 `sourcePlatform` 바로 뒤에 추가한다:

```java
    /** 근거 링크로 판별한 플랫폼 코드(Platform 이름, SP4 S4). 생성자가 정하고 바뀌지 않는다. */
    @Column(nullable = false, length = 20, updatable = false)
    private String platform;

    /** HMAC(기기 ID) — 원문은 저장하지 않는다(SP4 §4). 없으면 null. */
    @Column(name = "device_hash", length = 64, updatable = false)
    private String deviceHash;

    /** HMAC(IPv4 전체 또는 IPv6 /64). */
    @Column(name = "ip_hash", length = 64, updatable = false)
    private String ipHash;
```

10인자 생성자 끝에 `this.platform = PlatformResolver.resolve(evidenceUrl).name();`(import `kr.trendstage.domain.signal.PlatformResolver`). 11인자 생성자는 10인자를 부르므로 그대로. 게터·메서드 추가:

```java
    public String getPlatform() { return platform; }
    public String getDeviceHash() { return deviceHash; }
    public String getIpHash() { return ipHash; }

    /** 제보 출처 해시 — save 전에 한 번 부른다(컬럼이 updatable=false라 이후 변경은 반영되지 않는다). */
    public void recordOrigin(String deviceHash, String ipHash) {
        this.deviceHash = deviceHash;
        this.ipHash = ipHash;
    }
```

- [ ] **Step 5: 픽스처·시드 데이터**

`Fixtures.insertSubmission`의 INSERT에 `platform`을 넣는다(근거 링크 `https://example.com` → `ETC`):

```java
                "INSERT INTO submissions (user_id, trend_item_id, raw_input, normalized_key, confidence, "
                        + "source_platform, platform, evidence_url, one_line, created_at, is_seed) "
                        + "VALUES (?, ?, ?, ?, ?, 'X', 'ETC', 'https://example.com', '설명', ?, ?) RETURNING id",
```

그리고 추가:

```java
    public String platform(UUID submissionId) {
        return jdbc.queryForObject("SELECT platform FROM submissions WHERE id = ?", String.class, submissionId);
    }
```

`db/seed/app_flow_test_data.sql`: `INSERT INTO submissions (...)` 문마다 컬럼 목록의 `source_platform` 뒤에 `platform`을 넣고, 각 VALUES 행의 `source_platform` 값 뒤에 **그 행의 `evidence_url`을 `PlatformResolver`가 판별할 코드**(`'DCINSIDE'`·`'X'`·`'INSTAGRAM'`·`'YOUTUBE'`·`'ETC'` 등, Task 1 표)를 넣는다. 수동 적재용 파일이라 테스트가 돌리지 않는다 — 행 수를 세어 빠진 행이 없는지 눈으로 확인한다.

- [ ] **Step 6: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :app:test --tests "kr.trendstage.signal.SchemaV31Test"`
Expected: PASS

그리고 기존 통합 테스트가 V31에서 깨지지 않았는지: `<태스크>` = `:app:test --tests "kr.trendstage.judge.*" --tests "kr.trendstage.submission.*" --tests "kr.trendstage.merge.*"`
Expected: PASS

- [ ] **Step 7: 커밋**

```bash
git add backend/persistence/src/main/resources/db/migration/V31__sp4_verdict_signals.sql backend/persistence/src/main/java/db/migration/V31_1__backfill_submission_platform.java backend/persistence/src/main/resources/db/migration/V31_2__submission_platform_constraints.sql backend/persistence/src/main/java/kr/trendstage/persistence/entity/Submission.java backend/persistence/src/main/resources/db/seed/app_flow_test_data.sql backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/signal/SchemaV31Test.java
git commit -m "feat(schema): V31 — 제보 플랫폼 코드(링크 판별 백필)·기기/IP 해시 컬럼·불변 백테스트 데이터셋

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 기기·IP 해시 수집과 플랫폼 표시

**Files:**
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/SignalHasher.java`
- Create: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionOrigin.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/SubmissionController.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/web/SubmissionCreateRequest.java`
- Modify: `backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java` (제보 행 플랫폼 라벨)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java` (플랫폼 라벨)
- Modify: `backend/domain-core/src/main/java/kr/trendstage/domain/trend/StageEvaluator.java` (Javadoc만)
- Modify: `backend/app/src/main/resources/application.yml`
- Modify: `backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java`, `backend/app/src/test/java/kr/trendstage/admin/{AccountBootstrapModeTest,AdminAccountBootstrapTest}.java`, `backend/app/src/test/java/kr/trendstage/sla/SlaLastAdminTest.java` (속성 한 줄)
- Modify: `SubmissionService.create(userId, req)`를 부르는 테스트(`grep -rn "SubmissionService" backend/app/src/test`) — 세 번째 인자 `SubmissionOrigin.NONE`
- Test: `backend/app/src/test/java/kr/trendstage/signal/{SignalHasherTest,SubmissionOriginTest}.java`

**Interfaces:**
- Consumes: Task 3 `Submission#recordOrigin`, `#getPlatform`; Task 1 `Platform.labelOf`
- Produces:
  - `SignalHasher(String secret)` — `device(String): String`(null/빈 값 → null), `ip(String remoteAddr): String`, `MAX_DEVICE_ID = 128`
  - `record SubmissionOrigin(String deviceId, String remoteAddr)` — `NONE`
  - `SubmissionService.create(UUID userId, SubmissionCreateRequest req, SubmissionOrigin origin)` — **2인자 오버로드는 없앤다**(self-invocation 없이 한 경로)
  - `SubmissionRepository.findDistinctPlatforms(...)`가 **플랫폼 코드**를 돌려준다

- [ ] **Step 1: 테스트 속성 추가**

네 `@SpringBootTest`의 `properties` 배열에 한 줄을 더한다:

```java
        // 제보 기기·IP 해시 비밀값(SP4 §4) — 비어 있으면 기동 실패
        "signal.hash-secret=test-signal-secret",
```

- [ ] **Step 2: 실패하는 테스트 작성**

`SignalHasherTest.java`(스프링 없이):

```java
package kr.trendstage.signal;

import kr.trendstage.apipublic.service.SignalHasher;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignalHasherTest {

    private final SignalHasher hasher = new SignalHasher("k1");

    @Test
    void blankSecretFailsFast() {
        assertThatThrownBy(() -> new SignalHasher("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignalHasher("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SignalHasher(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hashesAreLowercaseHex64AndNeverTheRawValue() {
        String h = hasher.device("device-abc-123");
        assertThat(h).matches("^[0-9a-f]{64}$").doesNotContain("device-abc");
        assertThat(hasher.ip("203.0.113.7")).matches("^[0-9a-f]{64}$");
    }

    @Test
    void emptyInputsGiveNull() {
        assertThat(hasher.device(null)).isNull();
        assertThat(hasher.device("  ")).isNull();
        assertThat(hasher.ip(null)).isNull();
    }

    @Test
    void deviceIsTrimmedAndDomainSeparatedFromIp() {
        assertThat(hasher.device(" abc ")).isEqualTo(hasher.device("abc"));
        assertThat(hasher.device("abc")).isNotEqualTo(hasher.ip("abc"));
    }

    @Test
    void ipv4UsesWholeAddressIpv6UsesSlash64() {
        assertThat(hasher.ip("203.0.113.7")).isNotEqualTo(hasher.ip("203.0.113.8"));
        assertThat(hasher.ip("2001:db8:1:2:aaaa::1")).isEqualTo(hasher.ip("2001:db8:1:2:bbbb::9"));
        assertThat(hasher.ip("2001:db8:1:2::1")).isNotEqualTo(hasher.ip("2001:db8:1:3::1"));
        assertThat(hasher.ip("::ffff:203.0.113.7")).isEqualTo(hasher.ip("203.0.113.7"));
    }

    @Test
    void secretMatters() {
        assertThat(new SignalHasher("k1").device("x")).isNotEqualTo(new SignalHasher("k2").device("x"));
    }
}
```

`SubmissionOriginTest.java`:

```java
package kr.trendstage.signal;

import kr.trendstage.persistence.repo.SubmissionRepository;
import kr.trendstage.persistence.type.SubmissionResult;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 제보 API가 기기·IP를 해시로만 남기고, 플랫폼은 링크로 정한다(SP4 §4·S12). */
class SubmissionOriginTest extends AbstractIntegrationTest {

    static final String DC = "https://gall.dcinside.com/board/view/?id=t&no=1";
    static final String X = "https://x.com/a/status/1";

    @Autowired SubmissionRepository submissions;

    private ResultActions submit(UUID user, String name, String platformOrNull, String url,
                                 String deviceId, String remoteAddr) throws Exception {
        String plat = platformOrNull == null ? "" : "\"platform\":\"" + platformOrNull + "\",";
        MockHttpServletRequestBuilder req = post("/v1/submissions")
                .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of())))
                .with(r -> { r.setRemoteAddr(remoteAddr); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"category\":\"MEME\"," + plat + "\"evidenceUrl\":\"" + url
                        + "\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"설명\"}");
        if (deviceId != null) req.header("X-Device-Id", deviceId);
        return mvc.perform(req);
    }

    private Map<String, Object> row(String name) {
        return jdbc.queryForMap("SELECT platform, source_platform, device_hash, ip_hash FROM submissions WHERE raw_input = ?", name);
    }

    private static String unique() {
        return "sig_" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void storesOnlyHashes() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "device-abc-123", "203.0.113.7").andExpect(status().isCreated());
        Map<String, Object> r = row(name);
        assertThat((String) r.get("device_hash")).matches("^[0-9a-f]{64}$").doesNotContain("device-abc");
        assertThat((String) r.get("ip_hash")).matches("^[0-9a-f]{64}$");
        assertThat(r.get("platform")).isEqualTo("DCINSIDE");
    }

    @Test
    void sameDeviceSameHash() throws Exception {
        String n1 = unique(), n2 = unique(), n3 = unique();
        submit(fx.user(), n1, null, DC, "shared-device", "203.0.113.1").andExpect(status().isCreated());
        submit(fx.user(), n2, null, DC, "shared-device", "203.0.113.2").andExpect(status().isCreated());
        submit(fx.user(), n3, null, DC, "other-device", "203.0.113.1").andExpect(status().isCreated());
        assertThat(row(n1).get("device_hash")).isEqualTo(row(n2).get("device_hash"));
        assertThat(row(n1).get("device_hash")).isNotEqualTo(row(n3).get("device_hash"));
        assertThat(row(n1).get("ip_hash")).isEqualTo(row(n3).get("ip_hash"));
    }

    @Test
    void missingHeaderStillSubmits() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, X, null, "203.0.113.9").andExpect(status().isCreated());
        assertThat(row(name).get("device_hash")).isNull();
        assertThat(row(name).get("ip_hash")).isNotNull();
        assertThat(row(name).get("platform")).isEqualTo("X");
    }

    @Test
    void tooLongDeviceIdIs422AndWritesNothing() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "d".repeat(129), "203.0.113.9").andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM submissions WHERE raw_input = ?", Integer.class, name)).isZero();
        submit(fx.user(), unique(), null, DC, "d".repeat(128), "203.0.113.9").andExpect(status().isCreated());
    }

    @Test
    void legacyClientStillSubmits() throws Exception {   // Review Focus 3
        String name = unique();
        submit(fx.user(), name, "인스타", DC, null, "203.0.113.9").andExpect(status().isCreated());
        Map<String, Object> r = row(name);
        assertThat(r.get("platform")).isEqualTo("DCINSIDE");       // 칩이 아니라 링크
        assertThat(r.get("source_platform")).isEqualTo("인스타");   // 보관용
        assertThat(r.get("device_hash")).isNull();
    }

    @Test
    void newClientOmitsPlatform() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, "dev", "203.0.113.9").andExpect(status().isCreated());
        assertThat(row(name).get("source_platform")).isNull();
    }

    @Test
    void distinctPlatformsAreCodes() throws Exception {
        String name = unique();
        submit(fx.user(), name, null, DC, null, "203.0.113.9").andExpect(status().isCreated());
        submit(fx.user(), name, null, X, null, "203.0.113.9").andExpect(status().isCreated());   // 다른 유저 → 같은 항목 합류
        UUID item = jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE raw_input = ? LIMIT 1", UUID.class, name);
        assertThat(submissions.findDistinctPlatforms(item, SubmissionResult.VOID)).containsExactlyInAnyOrder("DCINSIDE", "X");
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `<태스크>` = `:app:test --tests "kr.trendstage.signal.SignalHasherTest" --tests "kr.trendstage.signal.SubmissionOriginTest"`
Expected: 컴파일 실패(`SignalHasher` 없음)

- [ ] **Step 4: 구현**

`SignalHasher.java`:

```java
package kr.trendstage.apipublic.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

/**
 * 제보 기기 ID·IP → HMAC-SHA256(소문자 hex 64자)(SP4 §4). 원문은 어디에도 남기지 않는다 — 이 클래스는
 * 입력을 로그·예외 메시지에 넣지 않는다. 비밀값이 비어 있으면 기동을 막는다(없으면 해시가 사실상 공개 값이 된다).
 */
@Component
public class SignalHasher {

    public static final int MAX_DEVICE_ID = 128;

    private final SecretKeySpec key;

    public SignalHasher(@Value("${signal.hash-secret:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "signal.hash-secret(SIGNAL_HASH_SECRET)이 비어 있습니다 — 제보 기기·IP 해시에 필요합니다(SP4 §4)");
        }
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** 기기 ID → 해시. 없거나 비면 null. 길이 검사는 호출 측(SubmissionService)이 먼저 한다. */
    public String device(String deviceId) {
        if (deviceId == null || deviceId.isBlank()) return null;
        return hmac("device:" + deviceId.trim());
    }

    /** 원격 주소 → 해시. IPv4는 주소 전체, IPv6는 앞 /64(휴대폰은 뒤 64비트가 자주 바뀐다). */
    public String ip(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank()) return null;
        return hmac("ip:" + ipKey(remoteAddr.trim()));
    }

    static String ipKey(String addr) {
        if (!addr.contains(":")) return addr;
        try {
            InetAddress a = InetAddress.getByName(addr);   // IP 리터럴이라 DNS 조회가 없다
            if (a instanceof Inet6Address) {
                return HexFormat.of().formatHex(a.getAddress(), 0, 8) + "/64";
            }
            return a.getHostAddress();   // ::ffff:1.2.3.4 → IPv4
        } catch (UnknownHostException e) {
            return addr;
        }
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
        }
    }
}
```

`SubmissionOrigin.java`:

```java
package kr.trendstage.apipublic.service;

/** 제보 요청의 출처 — 서비스가 해시로만 바꿔 저장한다(SP4 §4). 원문을 로그에 남기지 않는다. */
public record SubmissionOrigin(String deviceId, String remoteAddr) {
    public static final SubmissionOrigin NONE = new SubmissionOrigin(null, null);
}
```

`SubmissionService`: 생성자에 `SignalHasher hasher`를 추가하고 `create`를 3인자로 바꾼다.

```java
    @Transactional
    public SubmissionMineResponse create(UUID userId, SubmissionCreateRequest req, SubmissionOrigin origin) {
        if (!VALID_CONFIDENCE.contains(req.confidence())) {
            throw new SubmissionValidationException("confidence는 10/30/50 중 하나여야 합니다");
        }
        if (origin.deviceId() != null && origin.deviceId().length() > SignalHasher.MAX_DEVICE_ID) {
            throw new SubmissionValidationException("X-Device-Id는 " + SignalHasher.MAX_DEVICE_ID + "자 이하여야 합니다");
        }
        // … (기존 흐름 그대로) …
        Submission sub = new Submission(
                userId, item.getId(), req.name(), normalized,
                req.confidence().shortValue(), req.platform(), req.evidenceUrl(), req.oneLine(),
                req.disclosure(), false, now);
        sub.recordOrigin(hasher.device(origin.deviceId()), hasher.ip(origin.remoteAddr()));
        submissions.save(sub);
        return toResponse(sub, item);
    }
```

클래스 Javadoc에 "기기 ID·IP는 `SignalHasher`로 해시만 저장한다(SP4 §4). 플랫폼은 엔티티가 근거 링크로 정하고, 요청의 `platform`은 보관용(S12)." 한 줄을 더한다.

`SubmissionCreateRequest`: `platform`을 `@Size(max = 60) String platform`(선택)으로 바꾸고 주석 `// 옛 앱 빌드의 칩 값 — 보관만 한다(S12). 판정·표시는 근거 링크로.`

`SubmissionController.create`:

```java
    @PostMapping("/v1/submissions")
    public ResponseEntity<SubmissionMineResponse> create(Authentication auth,
                                                          @Valid @RequestBody SubmissionCreateRequest req,
                                                          @RequestHeader(value = "X-Device-Id", required = false) String deviceId,
                                                          HttpServletRequest request) {
        var response = service.create(userId(auth), req, new SubmissionOrigin(deviceId, request.getRemoteAddr()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
```
(import `jakarta.servlet.http.HttpServletRequest`, `kr.trendstage.apipublic.service.SubmissionOrigin`)

테스트의 `service.create(u, req(...))` 호출을 `service.create(u, req(...), SubmissionOrigin.NONE)`로 바꾼다.

`SubmissionRepository.findDistinctPlatforms`:

```java
    /** 서로 다른 플랫폼 코드(근거 링크 판별, SP4 S4). 홈 카드 경로·표시 단계용 — 라벨은 Platform.labelOf. */
    @Query("select distinct s.platform from Submission s where s.trendItemId = :id and s.result <> :excluded")
    List<String> findDistinctPlatforms(@Param("id") UUID trendItemId, @Param("excluded") SubmissionResult excluded);
```

`TrendQueryService`(import `kr.trendstage.domain.signal.Platform`, `java.util.stream.Collectors`):
- `toSummary`: `String pathText = platforms.stream().map(Platform::labelOf).collect(Collectors.joining(" → "));`
- `buildPropagationPath`: `getSourcePlatform()` 두 곳을 `Platform.labelOf(s.getPlatform())`로(필터 `s.getSourcePlatform() != null`은 삭제 — `platform`은 NOT NULL)

`StageEvaluator` Javadoc: "제보 자체에 적힌 최초 목격 플랫폼(source_platform)" → "제보 근거 링크로 판별한 플랫폼(submissions.platform, SP4)".

`AdminSeedService.SeedSubmissionRequest`: `@NotBlank @Size(max = 60) String platform` → `@Size(max = 60) String platform`(주석 `// 보관용(S12) — 콘솔은 보내지 않는다`).

`TrendItemAdminService.detail`의 `SubmissionRow`: `s.getSourcePlatform()` → `Platform.labelOf(s.getPlatform())`.
`MergeQueueController.toResponse`: `new KV("플랫폼", founding.getSourcePlatform())` → `new KV("플랫폼", Platform.labelOf(founding.getPlatform()))`.

`application.yml` — `firebase:` 블록 앞에:

```yaml
# 제보 기기 ID·IP를 해시하는 서버 비밀값(SP4 §4). 비어 있으면 기동 실패 — infra/.env(또는 로컬 app/.env)에 둔다.
# 바꾸면 이전 제보와 같은 기기·IP로 묶이지 않는다.
signal:
  hash-secret: ${SIGNAL_HASH_SECRET:}
```

`server:` 블록에:

```yaml
  # 프록시 뒤에 둘 때만 native/framework로 — 믿을 수 없는 X-Forwarded-For로 IP 해시를 속이지 못하게 기본은 none(SP4 §4).
  forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:none}
```

- [ ] **Step 5: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :app:test --tests "kr.trendstage.signal.*" --tests "kr.trendstage.submission.*" --tests "kr.trendstage.admin.*" --tests "kr.trendstage.sla.*"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/api-public/src/main/java/kr/trendstage/apipublic/service/SignalHasher.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionOrigin.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/SubmissionService.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/SubmissionController.java backend/api-public/src/main/java/kr/trendstage/apipublic/web/SubmissionCreateRequest.java backend/api-public/src/main/java/kr/trendstage/apipublic/service/TrendQueryService.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/seed/AdminSeedService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/MergeQueueController.java backend/domain-core/src/main/java/kr/trendstage/domain/trend/StageEvaluator.java backend/app/src/main/resources/application.yml backend/app/src/test/java/kr/trendstage/support/AbstractIntegrationTest.java backend/app/src/test/java/kr/trendstage/admin/AccountBootstrapModeTest.java backend/app/src/test/java/kr/trendstage/admin/AdminAccountBootstrapTest.java backend/app/src/test/java/kr/trendstage/sla/SlaLastAdminTest.java backend/app/src/test/java/kr/trendstage/signal/SignalHasherTest.java backend/app/src/test/java/kr/trendstage/signal/SubmissionOriginTest.java
# + SubmissionService.create를 부르던 테스트 파일
git commit -m "feat(submission): 기기 ID·IP를 HMAC 해시로만 저장, 플랫폼 표시를 링크 판별 코드로(SP4 §4·S12)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 판정 파이프라인 — 수집·동결·재판정·산정 근거

**Files:**
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java`
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/ParamsSnapshot.java`
- Modify: `backend/judge/src/main/java/kr/trendstage/judge/VerdictEvidence.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictController.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/signal/{SignalCollectionTest,LegacyEvidenceTest}.java`

**Interfaces:**
- Consumes: Task 2 `TrendSignal(deadline, entries, activeSubmitters)`, `TrendSignal.Entry` 9인자, `VerdictPlan#breakdown`, `TBreakdown#describe`, `SignalAxes.NEUTRAL`; Task 3 `Submission#getPlatform/#getDeviceHash/#getIpHash`
- Produces:
  - `SubmissionRepository.countActiveSubmitters(Instant from, Instant to, SubmissionResult excluded): long`
  - `record ParamsSnapshot(int submitterTarget, …16개…, SignalAxes axes)` — axes null이면 `toParameterSet()`가 NEUTRAL
  - `record VerdictEvidence(…기존 11개…, TBreakdown tBreakdown)`
  - `VerdictController.JudgedItem`에 `String tExplain`, `TrendItemAdminService.TrendItemDetail`에 `String previewExplain`(`previewScoreT` 바로 뒤)
  - `Fixtures#submissionFrom(UUID userId, UUID itemId, Instant createdAt, String evidenceUrl, String deviceHash, String ipHash): UUID`, `Fixtures#evidenceJson(UUID itemId): String`(가장 최근 `judged_at`)

- [ ] **Step 1: 픽스처 추가**

```java
    /** 출처가 있는 제보 — 플랫폼은 링크로 판별(엔티티와 같은 규칙), 해시는 그대로(소문자 hex 64자 또는 null). */
    public UUID submissionFrom(UUID userId, UUID itemId, Instant createdAt, String evidenceUrl,
                               String deviceHash, String ipHash) {
        String key = jdbc.queryForObject("SELECT normalized_key FROM trend_items WHERE id = ?", String.class, itemId);
        return jdbc.queryForObject("INSERT INTO submissions (user_id, trend_item_id, raw_input, normalized_key, confidence, "
                        + "platform, evidence_url, one_line, created_at, is_seed, device_hash, ip_hash) "
                        + "VALUES (?, ?, ?, ?, 30, ?, ?, '설명', ?, false, ?, ?) RETURNING id",
                UUID.class, userId, itemId, key, key, PlatformResolver.resolve(evidenceUrl).name(), evidenceUrl,
                Timestamp.from(createdAt), deviceHash, ipHash);
    }

    /** 항목의 가장 최근 판정 근거 JSON. */
    public String evidenceJson(UUID itemId) {
        return jdbc.queryForObject("SELECT evidence_json::text FROM verdicts WHERE trend_item_id = ? "
                + "ORDER BY judged_at DESC LIMIT 1", String.class, itemId);
    }
```
(import `kr.trendstage.domain.signal.PlatformResolver`)

- [ ] **Step 2: 실패하는 테스트 작성**

`LegacyEvidenceTest.java`(스프링 없이):

```java
package kr.trendstage.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.IndependenceMode;
import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.domain.signal.TBreakdown;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;
import kr.trendstage.judge.ParamsSnapshot;
import kr.trendstage.judge.VerdictEvidence;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** SP4 이전 판정 근거는 새 축을 중립으로 읽는다 — 0으로 채우지 않는다(S8). */
class LegacyEvidenceTest {

    private final ObjectMapper om = new ObjectMapper().findAndRegisterModules();

    /** SP1~SP3이 쓴 형식 그대로(params에 axes 없음, signal에 activeSubmitters 없음, entry에 platformCode 없음). */
    static final String LEGACY = """
            {"result":"MISS","reach":null,"t":0.1500,"deadline":"2026-09-15T00:00:00Z",
             "params":{"submitterTarget":20,"hitThreshold":0.2,"bandL2":0.35,"bandL3":0.55,"bandL4":0.75,
                       "mL1":0.2,"mL2":0.5,"mL3":1.0,"mL4":1.5,"wRank1":1.0,"wRank2":0.6,"wRank3":0.4,"wRankRest":0.2,
                       "halflifeDays":90,"tiAlpha":2.0,"tiBeta":3.0},
             "signal":{"deadline":"2026-09-15T00:00:00Z","entries":[
               {"submissionId":"00000000-0000-0000-0000-000000000001","userId":"00000000-0000-0000-0000-0000000000a1",
                "seed":false,"submittedAt":"2026-09-02T00:00:00Z","platform":"X","userJoinedAt":"2026-01-01T00:00:00Z"},
               {"submissionId":"00000000-0000-0000-0000-000000000002","userId":"00000000-0000-0000-0000-0000000000a2",
                "seed":false,"submittedAt":"2026-09-02T00:00:00Z","platform":"X","userJoinedAt":"2026-01-01T00:00:00Z"},
               {"submissionId":"00000000-0000-0000-0000-000000000003","userId":"00000000-0000-0000-0000-0000000000a3",
                "seed":false,"submittedAt":"2026-09-02T00:00:00Z","platform":"디시","userJoinedAt":"2026-01-01T00:00:00Z"}]},
             "distinctSubmitters":3,"distinctPlatforms":2,"orderRanks":{},"supersededVerdictId":null,"adminReason":null}
            """;

    @Test
    void legacyEvidenceReadsNeutral() throws Exception {
        VerdictEvidence ev = om.readValue(LEGACY, VerdictEvidence.class);
        ParameterSet p = ev.params().toParameterSet();
        assertThat(p.axes).isEqualTo(SignalAxes.NEUTRAL);
        assertThat(p.targetFloor).isEqualTo(20);
        assertThat(ev.signal().activeSubmitters()).isNull();
        assertThat(ev.signal().entries()).extracting(TrendSignal.Entry::platformCode).containsOnlyNulls();
        assertThat(ev.tBreakdown()).isNull();

        VerdictOutcome o = VerdictEngine.evaluate(ev.signal(), p);
        assertThat(o.result()).isEqualTo(VerdictResult.MISS);
        assertThat(o.t()).isEqualTo(0.15);
    }

    @Test
    void legacySignalStaysNeutralUnderAggressiveParams() throws Exception {
        VerdictEvidence ev = om.readValue(LEGACY, VerdictEvidence.class);
        ParameterSet d = ParameterSet.defaults();
        ParameterSet aggressive = new ParameterSet(20, d.hitThreshold, d.bandL2, d.bandL3, d.bandL4,
                d.mL1, d.mL2, d.mL3, d.mL4, d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta,
                new SignalAxes(0.5, 28, 1.0, 5, 0.5, 3, IndependenceMode.DEVICE_OR_IP));
        TBreakdown b = VerdictEngine.breakdown(ev.signal(), aggressive);
        assertThat(b.target()).isEqualTo(20);          // activeSubmitters 없음 → 하한
        assertThat(b.diversity()).isEqualTo(1.0);      // 코드 없음 → 판별 안 함
        assertThat(b.diversityApplied()).isFalse();
        assertThat(b.independent()).isEqualTo(3);      // 그룹 없음 → 압축 없음
    }

    @Test
    void newSnapshotRoundTripsAxes() throws Exception {
        ParameterSet d = ParameterSet.defaults();
        SignalAxes axes = new SignalAxes(0.1, 30, 0.5, 4, 0.6, 4, IndependenceMode.DEVICE);
        ParamsSnapshot snap = ParamsSnapshot.of(new ParameterSet(8, 0.3, d.bandL2, d.bandL3, d.bandL4,
                d.mL1, d.mL2, d.mL3, d.mL4, d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta, axes));
        ParamsSnapshot back = om.readValue(om.writeValueAsString(snap), ParamsSnapshot.class);
        assertThat(back).isEqualTo(snap);
        assertThat(back.toParameterSet().axes).isEqualTo(axes);
        assertThat(back.toParameterSet().targetFloor).isEqualTo(8);
    }
}
```

`SignalCollectionTest.java`:

```java
package kr.trendstage.signal;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.judge.AdjustmentPolicy;
import kr.trendstage.judge.JudgeOutcome;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.judge.VerdictEvidence;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 판정이 플랫폼 코드·그룹 번호·활성 제보자 수·T 분해를 동결하고 해시는 남기지 않는다(SP4 S2·S7).
 * 활성 제보자 수를 정확히 세려고 이 클래스만 2033·2034·2035년을 쓴다(Global Constraints).
 */
class SignalCollectionTest extends AbstractIntegrationTest {

    static final String DC = "https://gall.dcinside.com/board/view/?id=t&no=1";
    static final String X = "https://x.com/a/status/1";
    static final String IG = "https://www.instagram.com/p/a/";
    static final String DEV_A = "a".repeat(64), DEV_B = "b".repeat(64), IP_C = "c".repeat(64);

    @Autowired JudgeService judge;
    @Autowired ObjectMapper objectMapper;

    private VerdictEvidence evidence(UUID item) throws Exception {
        return objectMapper.readValue(fx.evidenceJson(item), VerdictEvidence.class);
    }

    @Test
    void evidenceFreezesGroupsAndActiveSubmittersButNoHashes() throws Exception {
        Instant first = Instant.parse("2033-03-01T00:00:00Z");
        UUID item = fx.item(first);
        fx.submissionFrom(fx.user(), item, first.plus(Duration.ofHours(1)), DC, DEV_A, IP_C);
        fx.submissionFrom(fx.user(), item, first.plus(Duration.ofHours(2)), X, DEV_A, IP_C);
        fx.submissionFrom(fx.user(), item, first.plus(Duration.ofDays(2)), IG, DEV_B, IP_C);
        fx.submissionFrom(fx.user(), item, first.plus(Duration.ofDays(3)), DC, null, null);
        fx.seedSubmission(fx.user(), item, first.plus(Duration.ofDays(4)));
        Instant judgedAt = first.plus(Duration.ofDays(15));
        judge.closeDue(judgedAt);
        judge.judge(item, judgedAt);

        String json = fx.evidenceJson(item);
        assertThat(json).doesNotContain(DEV_A).doesNotContain(DEV_B).doesNotContain(IP_C);
        VerdictEvidence ev = objectMapper.readValue(json, VerdictEvidence.class);
        assertThat(ev.signal().activeSubmitters()).isEqualTo(4);   // 시딩 제외
        List<TrendSignal.Entry> real = ev.signal().entries().stream().filter(e -> !e.seed()).toList();
        assertThat(real).extracting(TrendSignal.Entry::deviceGroup).containsExactly(1, 1, 2, null);
        assertThat(real).extracting(TrendSignal.Entry::ipGroup).containsExactly(1, 1, 1, null);
        assertThat(real).extracting(TrendSignal.Entry::platformCode).containsExactly("DCINSIDE", "X", "INSTAGRAM", "DCINSIDE");
        assertThat(real).extracting(TrendSignal.Entry::platform).containsOnlyNulls();
        assertThat(ev.tBreakdown()).isNotNull();
        assertThat(ev.tBreakdown().target()).isEqualTo(20);        // 기본값: 하한
        assertThat(ev.tBreakdown().independent()).isEqualTo(4);    // 기본값: 압축 꺼짐
        assertThat(ev.params().axes()).isNotNull();
    }

    @Test
    void activeSubmittersCountsWindowAcrossItems() throws Exception {
        Instant first = Instant.parse("2034-03-01T00:00:00Z");
        Instant deadline = first.plus(Duration.ofDays(14));
        UUID item = fx.item(first);
        fx.submissionFrom(fx.user(), item, first.plusSeconds(60), DC, null, null);
        UUID other = fx.item(first.minus(Duration.ofDays(40)));
        fx.submission(fx.user(), other, 30, deadline.minus(Duration.ofDays(20)));   // 창(28일) 안
        fx.submission(fx.user(), other, 30, deadline.minus(Duration.ofDays(29)));   // 창 밖
        UUID voided = fx.submission(fx.user(), other, 30, deadline.minus(Duration.ofDays(5)));
        fx.voidSubmission(voided, deadline.minus(Duration.ofDays(4)));               // VOID는 안 셈
        judge.closeDue(deadline.plusSeconds(1));
        judge.judge(item, deadline.plusSeconds(1));

        assertThat(evidence(item).signal().activeSubmitters()).isEqualTo(2);
    }

    @Test
    void rejudgeReusesFrozenActiveSubmitters() throws Exception {
        Instant first = Instant.parse("2035-03-01T00:00:00Z");
        UUID item = fx.item(first);
        for (int i = 0; i < 3; i++) fx.submissionFrom(fx.user(), item, first.plus(Duration.ofHours(i + 1)), DC, null, null);
        Instant judgedAt = first.plus(Duration.ofDays(15));
        judge.closeDue(judgedAt);
        judge.judge(item, judgedAt);
        assertThat(evidence(item).signal().activeSubmitters()).isEqualTo(3);

        // 판정 뒤에 같은 창 안으로 제보가 더 생겨도(백데이트) 재판정은 원 판정의 값을 쓴다(S2)
        UUID other = fx.item(first);
        fx.submission(fx.user(), other, 30, first.plus(Duration.ofDays(3)));
        JudgeOutcome out = judge.rejudge(item, "테스트", judgedAt.plusSeconds(60), AdjustmentPolicy.limitedTo(new BigDecimal("100")));

        assertThat(out).isInstanceOf(JudgeOutcome.Applied.class);
        assertThat(((JudgeOutcome.Applied) out).adjTotal()).isEqualByComparingTo("0");
        assertThat(evidence(item).signal().activeSubmitters()).isEqualTo(3);
    }

    @Test
    void adminResponsesExplainT() throws Exception {
        UUID admin = fx.admin();
        Instant first = Instant.parse("2035-09-01T00:00:00Z");
        UUID judged = fx.item(first);
        fx.submissionFrom(fx.user(), judged, first.plusSeconds(60), DC, null, null);
        fx.submissionFrom(fx.user(), judged, first.plusSeconds(120), X, null, null);
        Instant judgedAt = first.plus(Duration.ofDays(15));
        judge.closeDue(judgedAt);
        judge.judge(judged, judgedAt);

        mvc.perform(get("/admin/verdicts").with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.judged[?(@.trendItemId == '%s')].tExplain", judged.toString())
                        .value(hasItem(startsWith("T 0.10 = 제보자 2/20 (0.10)"))));

        UUID pending = fx.item(Instant.parse("2035-11-01T00:00:00Z"));
        fx.submissionFrom(fx.user(), pending, Instant.parse("2035-11-01T01:00:00Z"), DC, null, null);
        mvc.perform(get("/admin/trend-items/{id}", pending).with(asAdmin(admin, AdminRole.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previewExplain").value(startsWith("T 0.05 = 제보자 1/20 (0.05)")));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `<태스크>` = `:app:test --tests "kr.trendstage.signal.LegacyEvidenceTest" --tests "kr.trendstage.signal.SignalCollectionTest"`
Expected: 컴파일 실패(`tBreakdown()`·`axes()` 없음)

- [ ] **Step 4: 구현**

`SubmissionRepository`:

```java
    /** 상대 목표치 입력(SP4 S2) — [from, to) 안에 비VOID·비시딩 제보를 1건 이상 한 서로 다른 유저 수. */
    @Query("select count(distinct s.userId) from Submission s "
            + "where s.seed = false and s.result <> :excluded and s.createdAt >= :from and s.createdAt < :to")
    long countActiveSubmitters(@Param("from") Instant from, @Param("to") Instant to,
                               @Param("excluded") SubmissionResult excluded);
```

`ParamsSnapshot.java` 전체:

```java
package kr.trendstage.judge;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.SignalAxes;

/**
 * 판정 근거에 동결하는 파라미터(J2 — 재판정·VOID 차액의 기준). 컴포넌트 이름 submitterTarget은 SP4 이전 JSON과
 * 맞추려고 그대로 둔다 — 값은 목표치 하한(targetFloor). axes가 없으면(SP4 이전 근거) 중립으로 읽는다(S8).
 */
public record ParamsSnapshot(int submitterTarget, double hitThreshold, double bandL2, double bandL3, double bandL4,
                             double mL1, double mL2, double mL3, double mL4,
                             double wRank1, double wRank2, double wRank3, double wRankRest,
                             int halflifeDays, double tiAlpha, double tiBeta, SignalAxes axes) {

    public static ParamsSnapshot of(ParameterSet p) {
        return new ParamsSnapshot(p.targetFloor, p.hitThreshold, p.bandL2, p.bandL3, p.bandL4,
                p.mL1, p.mL2, p.mL3, p.mL4, p.wRank1, p.wRank2, p.wRank3, p.wRankRest,
                p.halflifeDays, p.tiAlpha, p.tiBeta, p.axes);
    }

    public ParameterSet toParameterSet() {
        return new ParameterSet(submitterTarget, hitThreshold, bandL2, bandL3, bandL4, mL1, mL2, mL3, mL4,
                wRank1, wRank2, wRank3, wRankRest, halflifeDays, tiAlpha, tiBeta,
                axes == null ? SignalAxes.NEUTRAL : axes);
    }
}
```

`VerdictEvidence`: 끝에 컴포넌트 `TBreakdown tBreakdown`(import `kr.trendstage.domain.signal.TBreakdown`)을 추가하고 Javadoc에 "SP4: signal에 활성 제보자 수·플랫폼 코드·그룹 번호(해시 없음, S7), tBreakdown = T 분해. SP4 이전 근거에는 없다(null)." 를 더한다.

`JudgeService`(import `java.time.Duration`, `java.util.HashMap`):

1. `judge()`의 수집:
```java
        ParameterSet p = params.resolve();
        Inputs in = collect(item, deadline, activeAt(deadline, p));
```
2. `rejudge()`의 수집:
```java
        Chain chain = chainOf(itemId);
        Inputs in = collect(item, deadlineOf(item), chain.activeSubmitters());
```
3. `voidItem()`의 VOID 근거: `new VerdictEvidence("VOID", null, null, null, null, null, 0, 0, Map.of(), current.get().getId(), reason, null)`
4. `Chain`·`chainOf`:
```java
    /** 판정 체인 — 원본의 파라미터·판정 시각·활성 제보자 수가 재판정·VOID 차액의 기준이다(J1·J2, SP4 S2). */
    record Chain(List<UUID> verdictIds, ParameterSet params, Instant anchor, Integer activeSubmitters) {}

    private Chain chainOf(UUID itemId) {
        List<Verdict> all = verdicts.findByTrendItemIdOrderByCreatedAtAsc(itemId);
        Verdict original = all.stream().filter(v -> v.getSupersedes() == null).findFirst()
                .orElseThrow(() -> new IllegalStateException("원본 판정이 없습니다: " + itemId));
        VerdictEvidence ev = read(original);
        if (ev.params() == null) {
            throw new IllegalStateException("원본 판정 근거에 파라미터가 없습니다: " + original.getId());
        }
        Integer active = ev.signal() == null ? null : ev.signal().activeSubmitters();
        return new Chain(all.stream().map(Verdict::getId).toList(), ev.params().toParameterSet(),
                original.getJudgedAt(), active);
    }
```
5. `preview()`:
```java
        ParameterSet p = params.resolve();
        Instant deadline = deadlineOf(item);
        Inputs in = collect(item, deadline, activeAt(deadline, p));
        return new Preview(in.signal(), VerdictComputation.run(in.signal(), in.refs(), p));
```
6. `activeAt`·`collect`·`group`:
```java
    /** 상대 목표치 입력(S2) — 관측 마감 직전 activeWindowDays일 동안 비VOID·비시딩 제보를 한 서로 다른 유저 수. */
    int activeAt(Instant deadline, ParameterSet p) {
        return (int) submissions.countActiveSubmitters(
                deadline.minus(Duration.ofDays(p.axes.activeWindowDays())), deadline, SubmissionResult.VOID);
    }

    /**
     * 관측 마감 전 비VOID 제보 → 판정 신호·점수 입력. 선점 순위는 시딩을 뺀 뷰(J3).
     * 기기·IP는 해시 대신 이 판정 안에서만 의미 있는 그룹 번호(제출 순 1, 2, …)로 넘긴다(S7).
     */
    Inputs collect(TrendItem item, Instant deadline, Integer activeSubmitters) {
        List<Submission> subs = submissions.findByTrendItemIdAndResultNot(item.getId(), SubmissionResult.VOID).stream()
                .filter(s -> s.getCreatedAt().isBefore(deadline))
                .sorted(Comparator.comparing(Submission::getCreatedAt))
                .toList();
        Map<UUID, Instant> joinedAt = users.findAllById(subs.stream().map(Submission::getUserId).distinct().toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, UserAccount::getJoinedAt));
        Map<UUID, Integer> ranks = orderRanks.findByTrendItemId(item.getId()).stream()
                .collect(Collectors.toMap(SubmissionOrderRank::getSubmissionId, SubmissionOrderRank::getOrderRank));

        Map<String, Integer> deviceGroups = new HashMap<>();
        Map<String, Integer> ipGroups = new HashMap<>();
        List<TrendSignal.Entry> entries = subs.stream().map(s -> new TrendSignal.Entry(
                s.getId(), s.getUserId(), s.isSeed(), s.getCreatedAt(), null, joinedAt.get(s.getUserId()),
                s.getPlatform(), group(deviceGroups, s.getDeviceHash()), group(ipGroups, s.getIpHash()))).toList();
        List<SubmissionRef> refs = subs.stream().map(s -> new SubmissionRef(
                s.getId(), s.getUserId(), s.getConfidence(),
                ranks.getOrDefault(s.getId(), Integer.MAX_VALUE), s.isSeed())).toList();
        return new Inputs(new TrendSignal(deadline, entries, activeSubmitters), refs, subs, ranks);
    }

    private static Integer group(Map<String, Integer> groups, String hash) {
        return hash == null ? null : groups.computeIfAbsent(hash, h -> groups.size() + 1);
    }
```
7. `evidence()`의 `new VerdictEvidence(...)` 끝 인자에 `plan.breakdown()` 추가.

`VerdictController`: 생성자에 `ObjectMapper objectMapper`를 받고, `JudgedItem`을

```java
    public record JudgedItem(String trendItemId, String canonicalName, String result, String reachLevel,
                              String scoreT, String judgedAt, boolean superseded, String tExplain) {}
```

로, `toJudgedItem`의 마지막 인자로 `current == null ? null : explain(current)`를 넘긴다:

```java
    /** 산정 근거(CLAUDE.md) — SP4 이전 판정·VOID는 분해가 없어 null. */
    private String explain(Verdict v) {
        try {
            TBreakdown b = objectMapper.readValue(v.getEvidenceJson(), VerdictEvidence.class).tBreakdown();
            return b == null ? null : b.describe();
        } catch (JsonProcessingException e) {
            return null;
        }
    }
```

`TrendItemAdminService.TrendItemDetail`: `String previewScoreT` 바로 뒤에 `String previewExplain`, 생성 인자는 `plan == null || plan.breakdown() == null ? null : plan.breakdown().describe()`.

- [ ] **Step 5: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :app:test --tests "kr.trendstage.signal.*" --tests "kr.trendstage.judge.*" --tests "kr.trendstage.approval.*"`
Expected: PASS(기존 `RejudgeAndVoidTest`·`VerdictApprovalTest`·`SimulationFromEvidenceTest` 포함)

Jackson이 `TrendSignal`/`Entry`를 역직렬화하지 못하면(레코드 생성자 탐지) 멈추고 보고한다 — 보조 생성자를 두지 않은 이유가 이것이다.

- [ ] **Step 6: 커밋**

```bash
git add backend/judge/src/main/java/kr/trendstage/judge/JudgeService.java backend/judge/src/main/java/kr/trendstage/judge/ParamsSnapshot.java backend/judge/src/main/java/kr/trendstage/judge/VerdictEvidence.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/SubmissionRepository.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/verdict/VerdictController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/trend/TrendItemAdminService.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/signal/LegacyEvidenceTest.java backend/app/src/test/java/kr/trendstage/signal/SignalCollectionTest.java
git commit -m "feat(judge): 판정 근거에 활성 제보자 수·플랫폼 코드·기기/IP 그룹 번호·T 분해 동결(해시 없음), 재판정은 원 판정 값 사용(SP4 S2·S7·S8)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: 스튜디오 9개 값 + 승인 조건

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/params/DraftValues.java`
- Modify: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java`
- Modify: `backend/app/src/test/java/kr/trendstage/support/Fixtures.java`
- Test: `backend/app/src/test/java/kr/trendstage/params/ParamStudioDraftTest.java`

**Interfaces:**
- Consumes: Task 2 `SignalAxes`, `IndependenceMode`, `ParameterSet`(17인자)
- Produces:
  - `record DraftValues(int targetFloor, double targetRatio, int activeWindowDays, double hitThreshold, double persistenceFloor, int persistenceFullDays, double diversityFloor, int diversityFullPlatforms, IndependenceMode independenceMode)` — 범위 위반 `IllegalArgumentException`(필드명으로 시작), `axes()`, `static of(ParameterSet)`, `toParameterSet()`
  - `ParameterDraft#toDraftValues(ObjectMapper)`(없는 키는 중립, `submitterTarget`은 `targetFloor`의 옛 이름), `#toParameterSet(ObjectMapper)`, `#getBacktestResult()`, `#recordBacktestResult(String)`, `#updatePayload`가 시뮬레이션·백테스트 결과를 **둘 다** 지운다
  - `ParamStudioService.updateDraftValues(UUID actorId, AdminRole role, DraftValues values)`, `requestApproval`은 시뮬레이션·백테스트 결과가 모두 있어야 한다(없으면 `AdminValidationException` → 422)
  - `ParamStudioController.ParameterDraftResponse(String draftId, String status, DraftValues values, DraftValues current, SimulationSummaryResponse simResult, JsonNode backtestResult)`; `PUT /admin/params/draft` 본문은 9개 필드(모두 필수)
  - `Fixtures#clearActiveDrafts()`

- [ ] **Step 1: 픽스처**

```java
    /** 전역 활성 드래프트(DRAFT·REVIEW)를 지운다 — 스튜디오 테스트의 시작과 끝에 부른다(드래프트는 전역 하나). */
    public void clearActiveDrafts() {
        jdbc.update("DELETE FROM parameter_drafts WHERE status IN ('DRAFT', 'REVIEW')");
    }
```

- [ ] **Step 2: 실패하는 테스트 작성**

```java
package kr.trendstage.params;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.SignalAxes;
import kr.trendstage.persistence.params.CurrentParameterSetResolver;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ADM-600 편집 항목 9개·승인 조건(SP4 §5.1·§5.3). 드래프트는 전역 하나라 시작·끝에 비운다. */
class ParamStudioDraftTest extends AbstractIntegrationTest {

    /** 예시 보정값(합성 시나리오가 전부 맞는 값, Task 8). */
    static final String EXAMPLE = """
            {"targetFloor":8,"targetRatio":0.1,"activeWindowDays":28,"hitThreshold":0.3,
             "persistenceFloor":0.5,"persistenceFullDays":4,"diversityFloor":0.5,"diversityFullPlatforms":3,
             "independenceMode":"DEVICE"}""";

    @Autowired CurrentParameterSetResolver resolver;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    private String with(String key, String rawValue) {
        return EXAMPLE.replaceFirst("\"" + key + "\":[^,}]+", "\"" + key + "\":" + rawValue);
    }

    @Test
    void savesNineValues() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.targetFloor").value(8))
                .andExpect(jsonPath("$.values.persistenceFullDays").value(4))
                .andExpect(jsonPath("$.values.independenceMode").value("DEVICE"))
                .andExpect(jsonPath("$.current.targetFloor").value(20))
                .andExpect(jsonPath("$.current.independenceMode").value("OFF"));
        mvc.perform(get("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR)))
                .andExpect(jsonPath("$.values.diversityFloor").value(0.5))
                .andExpect(jsonPath("$.values.targetRatio").value(0.1));
    }

    @Test
    void rangeViolationIs422WithFieldName() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("persistenceFloor", "1.5")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("persistenceFloor")));
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("targetFloor", "0")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("targetFloor")));
    }

    @Test
    void missingOrUnknownModeIs422() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE.replaceFirst(",\\s*\"independenceMode\":\"DEVICE\"", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("independenceMode: 값이 필요합니다"));
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("independenceMode", "\"IP\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(startsWith("independenceMode")));
    }

    @Test
    void editClearsSimulationAndBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isOk());
        jdbc.update("UPDATE parameter_drafts SET sim_result = '{\"changed\":0,\"total\":0,\"missToHit\":0,\"hitToMiss\":0,\"reachChanged\":0}'::jsonb, "
                + "backtest_result = '{\"caseCount\":1}'::jsonb WHERE status = 'DRAFT'");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(with("targetFloor", "9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simResult").value(nullValue()))
                .andExpect(jsonPath("$.backtestResult").value(nullValue()));
    }

    @Test
    void approvalRequiresBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"보정\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(containsString("백테스트")));
    }

    @Test
    void legacyPayloadReadsNeutral() {   // Review Focus 5
        UUID draft = fx.appliedDraft("{\"submitterTarget\":17,\"hitThreshold\":0.25}");
        try {
            ParameterSet p = resolver.resolve();
            assertThat(p.targetFloor).isEqualTo(17);
            assertThat(p.hitThreshold).isEqualTo(0.25);
            assertThat(p.axes).isEqualTo(SignalAxes.NEUTRAL);
        } finally {
            fx.deleteDraft(draft);
        }
    }

    @Test
    void auditorCannotWrite() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR))
                .contentType(MediaType.APPLICATION_JSON).content(EXAMPLE)).andExpect(status().isForbidden());
        mvc.perform(get("/admin/params/draft").with(asAdmin(auditor, AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NONE"));
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `<태스크>` = `:app:test --tests "kr.trendstage.params.ParamStudioDraftTest"`
Expected: FAIL(`$.values` 없음 등)

- [ ] **Step 4: 구현**

`DraftValues.java`:

```java
package kr.trendstage.domain.params;

import kr.trendstage.domain.signal.IndependenceMode;
import kr.trendstage.domain.signal.SignalAxes;

/**
 * ADM-600이 편집하는 값 9개(SP4 §5.1). 나머지 파라미터(구간·배수·선점·반감기·TI)는 defaults() 고정.
 * 범위를 벗어나면 IllegalArgumentException — 메시지는 필드명으로 시작한다.
 */
public record DraftValues(int targetFloor, double targetRatio, int activeWindowDays, double hitThreshold,
                          double persistenceFloor, int persistenceFullDays, double diversityFloor,
                          int diversityFullPlatforms, IndependenceMode independenceMode) {

    public DraftValues {
        if (targetFloor < 1) {
            throw new IllegalArgumentException("targetFloor: 1 이상이어야 합니다 (입력 " + targetFloor + ")");
        }
        SignalAxes.unit("hitThreshold", hitThreshold);
        new SignalAxes(targetRatio, activeWindowDays, persistenceFloor, persistenceFullDays,
                diversityFloor, diversityFullPlatforms, independenceMode);   // 나머지 범위 검증
    }

    public SignalAxes axes() {
        return new SignalAxes(targetRatio, activeWindowDays, persistenceFloor, persistenceFullDays,
                diversityFloor, diversityFullPlatforms, independenceMode);
    }

    public static DraftValues of(ParameterSet p) {
        SignalAxes a = p.axes;
        return new DraftValues(p.targetFloor, a.targetRatio(), a.activeWindowDays(), p.hitThreshold,
                a.persistenceFloor(), a.persistenceFullDays(), a.diversityFloor(), a.diversityFullPlatforms(),
                a.independenceMode());
    }

    public ParameterSet toParameterSet() {
        ParameterSet d = ParameterSet.defaults();
        return new ParameterSet(targetFloor, hitThreshold, d.bandL2, d.bandL3, d.bandL4,
                d.mL1, d.mL2, d.mL3, d.mL4, d.wRank1, d.wRank2, d.wRank3, d.wRankRest,
                d.halflifeDays, d.tiAlpha, d.tiBeta, axes());
    }
}
```

`ParameterDraft`: 필드·메서드 추가, `toParameterSet` 교체, `updatePayload`가 두 결과를 지우게(import `com.fasterxml.jackson.databind.JsonNode`, `kr.trendstage.domain.params.DraftValues`, `kr.trendstage.domain.signal.IndependenceMode`):

```java
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "backtest_result", columnDefinition = "jsonb")
    private String backtestResult;

    public String getBacktestResult() { return backtestResult; }

    /** 값을 고치면 이전 시뮬레이션·백테스트는 더 이상 이 값의 근거가 아니다(SP4 §5.1). */
    public void updatePayload(String payload) {
        this.payload = payload;
        this.simResult = null;
        this.backtestResult = null;
    }

    public void recordBacktestResult(String backtestResult) {
        this.backtestResult = backtestResult;
    }

    /**
     * payload(JSONB) → 편집 값 9개. 없는 키는 기본값(중립) — SP4 이전 payload(submitterTarget·hitThreshold만)도
     * 그대로 읽는다(S8). submitterTarget은 targetFloor의 옛 이름이다.
     */
    public DraftValues toDraftValues(ObjectMapper objectMapper) {
        try {
            JsonNode n = objectMapper.readTree(payload);
            DraftValues d = DraftValues.of(ParameterSet.defaults());
            int floor = n.hasNonNull("targetFloor") ? n.get("targetFloor").asInt()
                    : n.hasNonNull("submitterTarget") ? n.get("submitterTarget").asInt() : d.targetFloor();
            return new DraftValues(floor,
                    dbl(n, "targetRatio", d.targetRatio()),
                    integer(n, "activeWindowDays", d.activeWindowDays()),
                    dbl(n, "hitThreshold", d.hitThreshold()),
                    dbl(n, "persistenceFloor", d.persistenceFloor()),
                    integer(n, "persistenceFullDays", d.persistenceFullDays()),
                    dbl(n, "diversityFloor", d.diversityFloor()),
                    integer(n, "diversityFullPlatforms", d.diversityFullPlatforms()),
                    n.hasNonNull("independenceMode") ? IndependenceMode.valueOf(n.get("independenceMode").asText())
                            : d.independenceMode());
        } catch (Exception e) {
            throw new IllegalStateException("payload 파싱 실패: draft=" + id, e);
        }
    }

    public ParameterSet toParameterSet(ObjectMapper objectMapper) {
        return toDraftValues(objectMapper).toParameterSet();
    }

    private static double dbl(JsonNode n, String key, double fallback) {
        return n.hasNonNull(key) ? n.get(key).asDouble() : fallback;
    }

    private static int integer(JsonNode n, String key, int fallback) {
        return n.hasNonNull(key) ? n.get(key).asInt() : fallback;
    }
```
(기존 `toParameterSet` Javadoc "submitterTarget/hitThreshold 2개만…"은 지운다.)

`ParamStudioService`:

```java
    @Transactional
    public ParameterDraft updateDraftValues(UUID actorId, AdminRole actorRole, DraftValues values) {
        ParameterDraft draft = getOrCreateActiveDraft(actorId);
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("승인 대기 중인 드래프트는 수정할 수 없습니다");
        }
        draft.updatePayload(payloadJson(values));
        auditLogService.record(actorId, actorRole, "PARAM_DRAFT_UPDATE", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "targetFloor", values.targetFloor(), "targetRatio", values.targetRatio(),
                "activeWindowDays", values.activeWindowDays(), "hitThreshold", values.hitThreshold(),
                "persistenceFloor", values.persistenceFloor(), "persistenceFullDays", values.persistenceFullDays(),
                "diversityFloor", values.diversityFloor(), "diversityFullPlatforms", values.diversityFullPlatforms(),
                "independenceMode", values.independenceMode().name()));
        return draft;
    }
```

`requestApproval`의 시뮬레이션 검사 바로 뒤:

```java
        if (draft.getBacktestResult() == null) {
            throw new AdminValidationException("백테스트를 먼저 실행해야 승인 요청을 보낼 수 있습니다");
        }
```

`defaultPayloadJson()`은 `payloadJson(DraftValues.of(ParameterSet.defaults()))`, 옛 `payloadJson(int, double)`은 지우고:

```java
    private String payloadJson(DraftValues values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("드래프트 값 직렬화 실패", e);
        }
    }
```

클래스 Javadoc에 "승인 요청 조건: 마지막 수정 이후 시뮬레이션과 백테스트(SP4 S9)." 를 더한다.

`ParamStudioController`(import `com.fasterxml.jackson.databind.JsonNode`, `kr.trendstage.domain.params.DraftValues`, `kr.trendstage.domain.signal.IndependenceMode`, `kr.trendstage.apiadmin.auth.AdminValidationException`):

```java
    /** 9개 모두 필수 — 빠지면 "필드명: 값이 필요합니다"(422). */
    public record UpdateDraftRequest(Integer targetFloor, Double targetRatio, Integer activeWindowDays, Double hitThreshold,
                                     Double persistenceFloor, Integer persistenceFullDays, Double diversityFloor,
                                     Integer diversityFullPlatforms, String independenceMode) {
        DraftValues toValues() {
            IndependenceMode mode = mode(independenceMode);
            try {
                return new DraftValues(req(targetFloor, "targetFloor"), req(targetRatio, "targetRatio"),
                        req(activeWindowDays, "activeWindowDays"), req(hitThreshold, "hitThreshold"),
                        req(persistenceFloor, "persistenceFloor"), req(persistenceFullDays, "persistenceFullDays"),
                        req(diversityFloor, "diversityFloor"), req(diversityFullPlatforms, "diversityFullPlatforms"), mode);
            } catch (IllegalArgumentException e) {
                throw new AdminValidationException(e.getMessage());
            }
        }

        private static <T> T req(T value, String name) {
            if (value == null) throw new AdminValidationException(name + ": 값이 필요합니다");
            return value;
        }

        private static IndependenceMode mode(String value) {
            if (value == null) throw new AdminValidationException("independenceMode: 값이 필요합니다");
            try {
                return IndependenceMode.valueOf(value);
            } catch (IllegalArgumentException e) {
                throw new AdminValidationException("independenceMode: OFF · DEVICE · DEVICE_OR_IP 중 하나여야 합니다");
            }
        }
    }

    public record ParameterDraftResponse(String draftId, String status, DraftValues values, DraftValues current,
                                          SimulationSummaryResponse simResult, JsonNode backtestResult) {}
```

`AdminValidationException`은 `RuntimeException`이라 `catch (IllegalArgumentException)`에 걸리지 않는다 — `req`·`mode`의 "필드명: 값이 필요합니다"는 그대로 422로 나가고, `DraftValues`·`SignalAxes`의 범위 위반(IAE)만 감싼다.

- `updateDraft`: `service.updateDraftValues(actor.id(), actor.role(), req.toValues())`
- `operationalView()`: `DraftValues current = DraftValues.of(service.currentOperationalParams()); return new ParameterDraftResponse(null, "NONE", current, current, null, null);`
- `toResponse(draft)`:

```java
    private ParameterDraftResponse toResponse(ParameterDraft draft) {
        try {
            SimulationSummaryResponse sim = null;
            if (draft.getSimResult() != null) {
                var s = objectMapper.readTree(draft.getSimResult());
                sim = new SimulationSummaryResponse(
                        s.get("changed").asInt(), s.get("total").asInt(),
                        s.get("missToHit").asInt(), s.get("hitToMiss").asInt(), s.get("reachChanged").asInt());
            }
            JsonNode backtest = draft.getBacktestResult() == null ? null : objectMapper.readTree(draft.getBacktestResult());
            return new ParameterDraftResponse(draft.getId().toString(), draft.getStatus().name(),
                    draft.toDraftValues(objectMapper), DraftValues.of(service.currentOperationalParams()), sim, backtest);
        } catch (Exception e) {
            throw new IllegalStateException("드래프트 응답 변환 실패: " + draft.getId(), e);
        }
    }
```

`toResponse`를 Task 8의 `/draft/backtest`도 쓴다 — `private`로 두고 Task 8이 같은 컨트롤러에 엔드포인트를 더한다.

`Fixtures.paramDraftInReview`의 payload(`{"submitterTarget":20,"hitThreshold":0.2}`)는 그대로 둔다 — 옛 형식 호환을 계속 확인하는 셈이다.

- [ ] **Step 5: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :app:test --tests "kr.trendstage.params.*" --tests "kr.trendstage.approval.*" --tests "kr.trendstage.judge.*" --tests "kr.trendstage.admin.RoleMatrixTest"`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/params/DraftValues.java backend/persistence/src/main/java/kr/trendstage/persistence/entity/ParameterDraft.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/ParamStudioService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java backend/app/src/test/java/kr/trendstage/support/Fixtures.java backend/app/src/test/java/kr/trendstage/params/ParamStudioDraftTest.java
git commit -m "feat(params): 스튜디오 편집 값 9개(옛 payload는 중립으로 읽음), 승인 요청에 백테스트 결과 필수(SP4 §5.1·S9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: 백테스트 계산(순수 함수)

**Files:**
- Create: `backend/domain-core/src/main/java/kr/trendstage/domain/backtest/{BacktestCase,BacktestReport,Backtest}.java`
- Test: `backend/domain-core/src/test/java/kr/trendstage/domain/backtest/BacktestTest.java`

**Interfaces:**
- Consumes: Task 2 `VerdictEngine.evaluate`, `VerdictOutcome#breakdown`, `TBreakdown#describe`
- Produces:
  - `record BacktestCase(String caseId, String title, VerdictResult label, ReachLevel labelReach, TrendSignal signal)`
  - `record BacktestReport(int caseCount, Side current, Side draft, int changedCount, List<CaseRow> rows)` with 중첩 `Confusion(int tp, int fp, int fn, int tn)`, `Side(Confusion confusion, Double precision, Double recall, Double reachAgreement, int reachCompared)`, `Outcome(VerdictResult result, ReachLevel reach, TBreakdown breakdown, String explain)`, `CaseRow(String caseId, String title, VerdictResult label, ReachLevel labelReach, Outcome current, Outcome draft, boolean changed)`
  - `Backtest.run(List<BacktestCase>, ParameterSet current, ParameterSet draft): BacktestReport`

- [ ] **Step 1: 실패하는 테스트 작성**

```java
package kr.trendstage.domain.backtest;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BacktestTest {

    static final Instant DL = Instant.parse("2026-06-15T00:00:00Z");
    final ParameterSet defaults = ParameterSet.defaults();

    @Test
    void confusionPrecisionRecallAndReach() {
        List<BacktestCase> cases = List.of(
                c("tp", VerdictResult.HIT, ReachLevel.L2, users(10, false)),   // T 0.50 → HIT L2
                c("fn", VerdictResult.HIT, null, users(1, false)),             // 0.05 → MISS
                c("fp", VerdictResult.MISS, null, users(8, false)),            // 0.40 → HIT
                c("tn", VerdictResult.MISS, null, users(3, true)));            // 시딩뿐 → 0 → MISS
        BacktestReport r = Backtest.run(cases, defaults, defaults);

        assertEquals(4, r.caseCount());
        assertEquals(new BacktestReport.Confusion(1, 1, 1, 1), r.current().confusion());
        assertEquals(0.5, r.current().precision());
        assertEquals(0.5, r.current().recall());
        assertEquals(1.0, r.current().reachAgreement());
        assertEquals(1, r.current().reachCompared());
        assertEquals(0, r.changedCount());
        assertTrue(r.rows().get(0).current().explain().startsWith("T 0.50 = 제보자 10/20"));
    }

    @Test
    void precisionIsNullWhenNothingPredictedHit() {
        BacktestReport r = Backtest.run(List.of(c("a", VerdictResult.HIT, null, users(1, false))), defaults, defaults);
        assertNull(r.current().precision());
        assertEquals(0.0, r.current().recall());
        assertNull(r.current().reachAgreement());
    }

    @Test
    void changedWhenDraftMovesResultOrReach() {
        ParameterSet d = defaults;
        ParameterSet draft = new ParameterSet(10, d.hitThreshold, d.bandL2, d.bandL3, d.bandL4, d.mL1, d.mL2, d.mL3, d.mL4,
                d.wRank1, d.wRank2, d.wRank3, d.wRankRest, d.halflifeDays, d.tiAlpha, d.tiBeta);
        List<BacktestCase> cases = List.of(
                c("tp", VerdictResult.HIT, null, users(10, false)),   // L2 → L4
                c("fn", VerdictResult.HIT, null, users(1, false)),    // MISS → MISS
                c("fp", VerdictResult.MISS, null, users(8, false)),   // L2 → L4
                c("tn", VerdictResult.MISS, null, users(3, true)));
        BacktestReport r = Backtest.run(cases, defaults, draft);
        assertEquals(2, r.changedCount());
        assertTrue(r.rows().get(0).changed());
        assertFalse(r.rows().get(1).changed());
        assertEquals(ReachLevel.L4, r.rows().get(0).draft().reach());
    }

    static BacktestCase c(String id, VerdictResult label, ReachLevel reach, TrendSignal s) {
        return new BacktestCase(id, "제목 " + id, label, reach, s);
    }

    static TrendSignal users(int n, boolean seed) {
        List<TrendSignal.Entry> es = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            es.add(new TrendSignal.Entry(UUID.randomUUID(), UUID.randomUUID(), seed, DL.minusSeconds(3600L * (i + 1)),
                    null, null, "X", null, null));
        }
        return new TrendSignal(DL, es, null);
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `<태스크>` = `:domain-core:test --tests "kr.trendstage.domain.backtest.BacktestTest"`
Expected: 컴파일 실패

- [ ] **Step 3: 구현**

`BacktestCase.java`:

```java
package kr.trendstage.domain.backtest;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;

/**
 * 백테스트 사례 한 건. label·labelReach는 사람이 사후에 붙인 정답 — 평가에만 쓰고 판정 입력이 아니다(SP4 S10).
 * 판정 입력은 signal(사례의 제보 시계열)뿐이다.
 */
public record BacktestCase(String caseId, String title, VerdictResult label, ReachLevel labelReach, TrendSignal signal) {}
```

`BacktestReport.java`:

```java
package kr.trendstage.domain.backtest;

import kr.trendstage.domain.signal.TBreakdown;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.List;

/**
 * 현재 운영값 vs 초안값 비교(SP4 §5.4). HIT가 양성. 분모가 0인 비율은 null(화면 "—").
 * reachAgreement는 정답 등급이 있고 정답·예측이 모두 HIT인 사례 중 등급이 같은 비율.
 */
public record BacktestReport(int caseCount, Side current, Side draft, int changedCount, List<CaseRow> rows) {

    public record Confusion(int tp, int fp, int fn, int tn) {}

    public record Side(Confusion confusion, Double precision, Double recall, Double reachAgreement, int reachCompared) {}

    public record Outcome(VerdictResult result, ReachLevel reach, TBreakdown breakdown, String explain) {}

    public record CaseRow(String caseId, String title, VerdictResult label, ReachLevel labelReach,
                          Outcome current, Outcome draft, boolean changed) {}
}
```

`Backtest.java`:

```java
package kr.trendstage.domain.backtest;

import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictEngine;
import kr.trendstage.domain.verdict.VerdictOutcome;
import kr.trendstage.domain.verdict.VerdictResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 백테스트(ADM-600, SP4 S9) 순수 함수. 운영 판정과 같은 VerdictEngine으로 사례마다 현재값·초안값을 계산해
 * 정답과 비교한다 — 드리프트 방지(운영/시뮬레이션/백테스트가 같은 엔진).
 */
public final class Backtest {
    private Backtest() {}

    public static BacktestReport run(List<BacktestCase> cases, ParameterSet current, ParameterSet draft) {
        List<BacktestReport.CaseRow> rows = new ArrayList<>();
        for (BacktestCase c : cases) {
            BacktestReport.Outcome now = outcome(c.signal(), current);
            BacktestReport.Outcome next = outcome(c.signal(), draft);
            boolean changed = now.result() != next.result() || now.reach() != next.reach();
            rows.add(new BacktestReport.CaseRow(c.caseId(), c.title(), c.label(), c.labelReach(), now, next, changed));
        }
        int changedCount = (int) rows.stream().filter(BacktestReport.CaseRow::changed).count();
        return new BacktestReport(cases.size(), side(rows, true), side(rows, false), changedCount, rows);
    }

    private static BacktestReport.Outcome outcome(TrendSignal signal, ParameterSet p) {
        VerdictOutcome o = VerdictEngine.evaluate(signal, p);
        return new BacktestReport.Outcome(o.result(), o.reach(), o.breakdown(), o.breakdown().describe());
    }

    private static BacktestReport.Side side(List<BacktestReport.CaseRow> rows, boolean current) {
        int tp = 0, fp = 0, fn = 0, tn = 0, compared = 0, agreed = 0;
        for (BacktestReport.CaseRow r : rows) {
            BacktestReport.Outcome o = current ? r.current() : r.draft();
            boolean predicted = o.result() == VerdictResult.HIT;
            boolean actual = r.label() == VerdictResult.HIT;
            if (predicted && actual) tp++;
            else if (predicted) fp++;
            else if (actual) fn++;
            else tn++;
            if (r.labelReach() != null && predicted && actual) {
                compared++;
                if (o.reach() == r.labelReach()) agreed++;
            }
        }
        return new BacktestReport.Side(new BacktestReport.Confusion(tp, fp, fn, tn),
                ratio(tp, tp + fp), ratio(tp, tp + fn), ratio(agreed, compared), compared);
    }

    private static Double ratio(int numerator, int denominator) {
        return denominator == 0 ? null : numerator / (double) denominator;
    }
}
```

- [ ] **Step 4: 통과 확인**

Run: `<태스크>` = `:domain-core:test`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add backend/domain-core/src/main/java/kr/trendstage/domain/backtest/BacktestCase.java backend/domain-core/src/main/java/kr/trendstage/domain/backtest/BacktestReport.java backend/domain-core/src/main/java/kr/trendstage/domain/backtest/Backtest.java backend/domain-core/src/test/java/kr/trendstage/domain/backtest/BacktestTest.java
git commit -m "feat(backtest): 현재값 vs 초안값 정밀도·재현율·확산 등급 일치율 순수 함수(SP4 §5.4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: 백테스트 데이터셋·API·합성 시나리오·완료 기준 경로

**Files:**
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/entity/BacktestDataset.java`
- Create: `backend/persistence/src/main/java/kr/trendstage/persistence/repo/BacktestDatasetRepository.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/BacktestDatasetParser.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/BacktestService.java`
- Create: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BacktestController.java`
- Create: `backend/api-admin/src/main/resources/backtest/synthetic-v1.json`
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java` (`POST /draft/backtest`)
- Modify: `backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ParamApplyExecutor.java` (`describe`)
- Test: `backend/app/src/test/java/kr/trendstage/params/{BacktestApiTest,SyntheticScenarioTest,ParamApplyEndToEndTest}.java`

**Interfaces:**
- Consumes: Task 1 `PlatformResolver`; Task 2 `TrendSignal`; Task 6 `DraftValues`, `ParameterDraft#recordBacktestResult`, `ParamStudioService#getOrCreateActiveDraft/#currentOperationalParams`, `ParamStudioController#toResponse`; Task 7 `Backtest.run`, `BacktestCase`, `BacktestReport`
- Produces:
  - `BacktestDatasetParser.parse(String raw): Parsed(String name, List<BacktestCase> cases)` — 위반 시 `AdminValidationException("경로: 메시지")`(예 `cases[0].submissions[1].at: deadline보다 앞서야 합니다`), 상수 `MAX_BYTES = 2 * 1024 * 1024`, `MAX_CASES = 500`
  - `BacktestService.upload(UUID, AdminRole, String): Upload(BacktestDataset dataset, boolean created)`, `list()`, `example(): String`, `run(UUID, AdminRole, UUID datasetId): ParameterDraft`; 결과 JSON = `BacktestResult(UUID datasetId, String datasetName, String sha256, int caseCount, Instant ranAt, BacktestReport report)`
  - `POST/GET /admin/params/backtest-datasets`, `GET /admin/params/backtest-datasets/example`, `POST /admin/params/draft/backtest {datasetId}`
  - `ParamApplyExecutor.describe`: `파라미터 적용 · 드래프트 xxxxxxxx · 데이터셋 '이름'(N건) — 정밀도 a→b, 재현율 c→d, 판정 변경 k건`

**합성 시나리오와 예시 보정값.** 예시 보정값 = `DraftValues(8, 0.10, 28, 0.30, 0.5, 4, 0.5, 3, DEVICE)`. 모든 사례의 관측 마감은 `2026-06-15T00:00:00Z`, 제보 시각 `T03:00:00Z`는 KST 12:00(같은 날짜)이다. 예시 보정값에서 12건 전부가 라벨과 맞고, 기본값에서는 옛 공식 그대로 TP 5·FP 4·FN 1·TN 2(정밀도 0.56, 재현율 0.83)다. 기본값 → 예시 보정값으로 판정(결과 또는 등급)이 바뀌는 사례는 c08·c10을 뺀 **10건**.

| 사례 | 드러내는 축 | 라벨 | 예시 보정값 T | 기본값 T |
|---|---|---|---|---|
| c01 | 자연 확산(8명·5일·3곳) | HIT L4 | 1.00 → L4 | 0.40 → L2 |
| c02 | 하루 몰림(5명·1일·2곳) | MISS | 0.625×0.625×0.75 = 0.293 | 0.25 → HIT L1 |
| c03 | 한 플랫폼 몰림(4명·4일·디시만) | MISS | 0.5×1×0.5 = 0.25 | 0.20 → HIT L1 |
| c04 | 같은 기기 무리(계정 6·기기 2) | MISS | 2/8 = 0.25 | 0.30 → HIT L1 |
| c05 | 공유 IP 정상 유저(6명·기기 6·IP 1) | HIT | 0.75 → L4 | 0.30 → HIT L1 |
| c06 | 소규모 진짜 유행(4명·4일·3곳) | HIT L2 | 0.50 → L2 | 0.20 → HIT L1 |
| c07 | 대규모 커뮤니티 대비 소수(활성 400, 10명) | MISS | 10/40 = 0.25 | 0.50 → HIT L2 |
| c08 | 시딩만 3건 | MISS | 0 | 0 |
| c09 | 3명·3일·3곳(경계 HIT) | HIT L1 | 0.375×0.875 = 0.328 → L1 | 0.15 → MISS |
| c10 | 2명·2일·2곳 | MISS | 0.25×0.75×0.75 = 0.141 | 0.10 → MISS |
| c11 | 활성 제보자 값 없음(6명·5일·3곳) | HIT | 6/8 = 0.75 → L4 | 0.30 → HIT L1 |
| c12 | 기기·IP 정보 없음(옛 앱, 6명·5일·3곳) | HIT | 0.75 → L4 | 0.30 → HIT L1 |

- [ ] **Step 1: 합성 시나리오 파일 작성**

`backend/api-admin/src/main/resources/backtest/synthetic-v1.json`:

```json
{
  "formatVersion": 1,
  "name": "합성 시나리오 v1",
  "note": "라벨은 이 설계가 의도한 판정이지 실측이 아니다 — 형식 견본·로직 검증용(SP4 §5.5). 예시 보정값: targetFloor 8, targetRatio 0.10, activeWindowDays 28, hitThreshold 0.30, persistenceFloor 0.5, persistenceFullDays 4, diversityFloor 0.5, diversityFullPlatforms 3, independenceMode DEVICE.",
  "cases": [
    {
      "caseId": "c01", "title": "자연 확산 — 여러 날·여러 플랫폼", "label": "HIT", "labelReach": "L4",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=101", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-01T05:00:00Z", "evidenceUrl": "https://x.com/user/status/102", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c103/", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-02T05:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=104", "device": "d4", "ip": "i4" },
        { "submitter": "u5", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://x.com/user/status/105", "device": "d5", "ip": "i5" },
        { "submitter": "u6", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c106/", "device": "d6", "ip": "i6" },
        { "submitter": "u7", "at": "2026-06-05T05:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=107", "device": "d7", "ip": "i7" },
        { "submitter": "u8", "at": "2026-06-07T03:00:00Z", "evidenceUrl": "https://x.com/user/status/108", "device": "d8", "ip": "i8" }
      ]
    },
    {
      "caseId": "c02", "title": "하루 몰림 — 단톡방 몰아주기", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-03T01:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=201", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-03T02:00:00Z", "evidenceUrl": "https://x.com/user/status/202", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=203", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-03T04:00:00Z", "evidenceUrl": "https://x.com/user/status/204", "device": "d4", "ip": "i4" },
        { "submitter": "u5", "at": "2026-06-03T05:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=205", "device": "d5", "ip": "i5" }
      ]
    },
    {
      "caseId": "c03", "title": "한 플랫폼 몰림 — 디시 안에서만", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=301", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=302", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://m.dcinside.com/board/trend/303", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-06T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=304", "device": "d4", "ip": "i4" }
      ]
    },
    {
      "caseId": "c04", "title": "같은 기기 무리 — 계정 6개, 기기 2대", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=401", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://x.com/user/status/402", "device": "d1", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c403/", "device": "d1", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=404", "device": "d1", "ip": "i4" },
        { "submitter": "u5", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://x.com/user/status/405", "device": "d1", "ip": "i5" },
        { "submitter": "u6", "at": "2026-06-06T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c406/", "device": "d2", "ip": "i6" }
      ]
    },
    {
      "caseId": "c05", "title": "공유 IP의 정상 유저 — DEVICE_OR_IP 오탐 사례", "label": "HIT",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=501", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://x.com/user/status/502", "device": "d2", "ip": "i1" },
        { "submitter": "u3", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c503/", "device": "d3", "ip": "i1" },
        { "submitter": "u4", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=504", "device": "d4", "ip": "i1" },
        { "submitter": "u5", "at": "2026-06-07T03:00:00Z", "evidenceUrl": "https://x.com/user/status/505", "device": "d5", "ip": "i1" },
        { "submitter": "u6", "at": "2026-06-09T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c506/", "device": "d6", "ip": "i1" }
      ]
    },
    {
      "caseId": "c06", "title": "소규모지만 며칠·여러 곳", "label": "HIT", "labelReach": "L2",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=601", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://x.com/user/status/602", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c603/", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-08T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=604", "device": "d4", "ip": "i4" }
      ]
    },
    {
      "caseId": "c07", "title": "큰 커뮤니티 대비 소수 — 상대 목표치", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 400,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=701", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-01T05:00:00Z", "evidenceUrl": "https://x.com/user/status/702", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c703/", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-02T05:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=704", "device": "d4", "ip": "i4" },
        { "submitter": "u5", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://x.com/user/status/705", "device": "d5", "ip": "i5" },
        { "submitter": "u6", "at": "2026-06-03T05:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c706/", "device": "d6", "ip": "i6" },
        { "submitter": "u7", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=707", "device": "d7", "ip": "i7" },
        { "submitter": "u8", "at": "2026-06-04T05:00:00Z", "evidenceUrl": "https://x.com/user/status/708", "device": "d8", "ip": "i8" },
        { "submitter": "u9", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c709/", "device": "d9", "ip": "i9" },
        { "submitter": "u10", "at": "2026-06-05T05:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=710", "device": "d10", "ip": "i10" }
      ]
    },
    {
      "caseId": "c08", "title": "시딩만 있는 항목", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "s1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=801", "seed": true },
        { "submitter": "s2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://x.com/user/status/802", "seed": true },
        { "submitter": "s3", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c803/", "seed": true }
      ]
    },
    {
      "caseId": "c09", "title": "경계 HIT — 3명이지만 3일·3곳", "label": "HIT", "labelReach": "L1",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=901", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-03T03:00:00Z", "evidenceUrl": "https://x.com/user/status/902", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-05T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c903/", "device": "d3", "ip": "i3" }
      ]
    },
    {
      "caseId": "c10", "title": "경계 MISS — 2명·2일·2곳", "label": "MISS",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=1001", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://x.com/user/status/1002", "device": "d2", "ip": "i2" }
      ]
    },
    {
      "caseId": "c11", "title": "활성 제보자 값 없음 — 목표치는 하한", "label": "HIT",
      "deadline": "2026-06-15T00:00:00Z",
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=1101", "device": "d1", "ip": "i1" },
        { "submitter": "u2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://x.com/user/status/1102", "device": "d2", "ip": "i2" },
        { "submitter": "u3", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c1103/", "device": "d3", "ip": "i3" },
        { "submitter": "u4", "at": "2026-06-06T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=1104", "device": "d4", "ip": "i4" },
        { "submitter": "u5", "at": "2026-06-06T05:00:00Z", "evidenceUrl": "https://x.com/user/status/1105", "device": "d5", "ip": "i5" },
        { "submitter": "u6", "at": "2026-06-08T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c1106/", "device": "d6", "ip": "i6" }
      ]
    },
    {
      "caseId": "c12", "title": "기기·IP 정보 없음 — 옛 앱 빌드", "label": "HIT",
      "deadline": "2026-06-15T00:00:00Z", "activeSubmitters": 60,
      "submissions": [
        { "submitter": "u1", "at": "2026-06-01T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=1201" },
        { "submitter": "u2", "at": "2026-06-02T03:00:00Z", "evidenceUrl": "https://x.com/user/status/1202" },
        { "submitter": "u3", "at": "2026-06-04T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c1203/" },
        { "submitter": "u4", "at": "2026-06-06T03:00:00Z", "evidenceUrl": "https://gall.dcinside.com/board/view/?id=trend&no=1204" },
        { "submitter": "u5", "at": "2026-06-06T05:00:00Z", "evidenceUrl": "https://x.com/user/status/1205" },
        { "submitter": "u6", "at": "2026-06-08T03:00:00Z", "evidenceUrl": "https://www.instagram.com/p/c1206/" }
      ]
    }
  ]
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`SyntheticScenarioTest.java`(스프링 없이):

```java
package kr.trendstage.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.params.BacktestDatasetParser;
import kr.trendstage.domain.backtest.Backtest;
import kr.trendstage.domain.backtest.BacktestCase;
import kr.trendstage.domain.backtest.BacktestReport;
import kr.trendstage.domain.params.DraftValues;
import kr.trendstage.domain.params.ParameterSet;
import kr.trendstage.domain.signal.IndependenceMode;
import kr.trendstage.domain.verdict.VerdictResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 합성 시나리오 12건(SP4 §5.5, 테스트 15). 라벨은 의도한 판정이지 실측이 아니다. */
class SyntheticScenarioTest {

    static final DraftValues EXAMPLE = new DraftValues(8, 0.10, 28, 0.30, 0.5, 4, 0.5, 3, IndependenceMode.DEVICE);

    private final BacktestDatasetParser parser = new BacktestDatasetParser(new ObjectMapper().findAndRegisterModules());

    private List<BacktestCase> cases() throws IOException {
        String raw = new ClassPathResource("backtest/synthetic-v1.json").getContentAsString(StandardCharsets.UTF_8);
        return parser.parse(raw).cases();
    }

    @Test
    void exampleCalibrationMatchesEveryLabel() throws IOException {
        BacktestReport r = Backtest.run(cases(), ParameterSet.defaults(), EXAMPLE.toParameterSet());
        assertThat(r.caseCount()).isEqualTo(12);
        assertThat(r.draft().confusion()).isEqualTo(new BacktestReport.Confusion(6, 0, 0, 6));
        assertThat(r.draft().precision()).isEqualTo(1.0);
        assertThat(r.draft().recall()).isEqualTo(1.0);
        assertThat(r.draft().reachAgreement()).isEqualTo(1.0);
        assertThat(r.draft().reachCompared()).isEqualTo(3);
        assertThat(r.rows()).allSatisfy(row -> assertThat(row.draft().result()).as(row.caseId()).isEqualTo(row.label()));
    }

    @Test
    void defaultsEqualLegacyFormula() throws IOException {
        List<BacktestCase> cases = cases();
        BacktestReport r = Backtest.run(cases, ParameterSet.defaults(), EXAMPLE.toParameterSet());
        assertThat(r.current().confusion()).isEqualTo(new BacktestReport.Confusion(5, 4, 1, 2));
        assertThat(r.current().reachCompared()).isEqualTo(2);
        assertThat(r.current().reachAgreement()).isEqualTo(0.0);
        assertThat(r.changedCount()).isEqualTo(10);
        for (int i = 0; i < cases.size(); i++) {
            double legacy = Math.max(0.0, Math.min(1.0, cases.get(i).signal().distinctSubmitters() / 20.0));
            assertThat(r.rows().get(i).current().breakdown().t()).as(cases.get(i).caseId()).isEqualTo(legacy);
        }
    }

    @Test
    void burstCaseExplainsItsPenalty() throws IOException {
        BacktestReport r = Backtest.run(cases(), ParameterSet.defaults(), EXAMPLE.toParameterSet());
        BacktestReport.CaseRow c02 = r.rows().stream().filter(row -> row.caseId().equals("c02")).findFirst().orElseThrow();
        assertThat(c02.draft().explain())
                .isEqualTo("T 0.29 = 제보자 5/8 (0.63) × 지속성 0.63 (1일/4일) × 다양성 0.75 (2곳/3곳)");
    }

    @Test
    void sharedIpShowsWhyDeviceOrIpStaysOff() throws IOException {
        DraftValues orIp = new DraftValues(8, 0.10, 28, 0.30, 0.5, 4, 0.5, 3, IndependenceMode.DEVICE_OR_IP);
        BacktestReport r = Backtest.run(cases(), ParameterSet.defaults(), orIp.toParameterSet());
        BacktestReport.CaseRow c05 = r.rows().stream().filter(row -> row.caseId().equals("c05")).findFirst().orElseThrow();
        assertThat(c05.label()).isEqualTo(VerdictResult.HIT);
        assertThat(c05.draft().result()).isEqualTo(VerdictResult.MISS);   // 공유 IP로 6명이 1명이 된다
    }
}
```

`BacktestApiTest.java`:

```java
package kr.trendstage.params;

import com.jayway.jsonpath.JsonPath;
import kr.trendstage.apiadmin.approval.ParamApplyExecutor;
import kr.trendstage.apiadmin.params.BacktestDatasetParser;
import kr.trendstage.persistence.repo.ApprovalRequestRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.StringJoiner;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 백테스트 데이터셋 업로드·실행·승인 요약(SP4 §5.2~§5.4, 테스트 13·14). */
class BacktestApiTest extends AbstractIntegrationTest {

    @Autowired BacktestDatasetParser parser;
    @Autowired ParamApplyExecutor paramApplyExecutor;
    @Autowired ApprovalRequestRepository approvals;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    static String rand() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    static String dataset(String name, String... cases) {
        return "{\"formatVersion\":1,\"name\":\"" + name + "\",\"cases\":[" + String.join(",", cases) + "]}";
    }

    static String oneCase(String caseId) {
        return "{\"caseId\":\"" + caseId + "\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"activeSubmitters\":60,\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T03:00:00Z\","
                + "\"evidenceUrl\":\"https://x.com/a\"}]}";
    }

    private String upload(UUID actor, AdminRole role, String body, int expectedStatus) throws Exception {
        return mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(actor, role))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void uploadReportsErrorPosition() throws Exception {
        UUID op = fx.admin("OPERATOR");
        String bad = dataset("bad-" + rand(), "{\"caseId\":\"c1\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T00:00:00Z\",\"evidenceUrl\":\"https://x.com/a\"},"
                + "{\"submitter\":\"u2\",\"at\":\"2026-06-16T00:00:00Z\",\"evidenceUrl\":\"https://x.com/b\"}]}");
        String res = upload(op, AdminRole.OPERATOR, bad, 422);
        assertThat((String) JsonPath.read(res, "$.detail")).isEqualTo("cases[0].submissions[1].at: deadline보다 앞서야 합니다");
    }

    @Test
    void sameFileReturnsSameDataset() throws Exception {
        UUID op = fx.admin("OPERATOR");
        String body = dataset("dup-" + rand(), oneCase("c1"));
        String first = upload(op, AdminRole.OPERATOR, body, 201);
        String again = upload(op, AdminRole.OPERATOR, body, 200);
        assertThat((String) JsonPath.read(again, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
        assertThat((Integer) JsonPath.read(first, "$.caseCount")).isEqualTo(1);
    }

    @Test
    void rejectsTooManyCasesAndOversizedFiles() throws Exception {
        UUID op = fx.admin("OPERATOR");
        StringJoiner many = new StringJoiner(",");
        for (int i = 0; i < 501; i++) many.add(oneCase("c" + i));
        String res = upload(op, AdminRole.OPERATOR, "{\"formatVersion\":1,\"name\":\"many\",\"cases\":[" + many + "]}", 422);
        assertThat((String) JsonPath.read(res, "$.detail")).startsWith("cases: 500건 이하");

        String huge = "{\"formatVersion\":1,\"name\":\"huge\",\"padding\":\"" + "x".repeat(2 * 1024 * 1024) + "\",\"cases\":[" + oneCase("c1") + "]}";
        res = upload(op, AdminRole.OPERATOR, huge, 422);
        assertThat((String) JsonPath.read(res, "$.detail")).isEqualTo("사례 파일은 2MB 이하여야 합니다");
    }

    @Test
    void sameSubmitterTwiceCountsOnce() {   // Review Focus 4
        String body = dataset("twice", "{\"caseId\":\"c1\",\"title\":\"t\",\"label\":\"HIT\",\"deadline\":\"2026-06-15T00:00:00Z\","
                + "\"submissions\":[{\"submitter\":\"u1\",\"at\":\"2026-06-01T03:00:00Z\",\"evidenceUrl\":\"https://x.com/a\"},"
                + "{\"submitter\":\"u1\",\"at\":\"2026-06-02T03:00:00Z\",\"evidenceUrl\":\"https://x.com/b\"},"
                + "{\"submitter\":\"u2\",\"at\":\"2026-06-03T03:00:00Z\",\"evidenceUrl\":\"https://x.com/c\"}]}");
        var parsed = parser.parse(body);
        assertThat(parsed.cases().get(0).signal().distinctSubmitters()).isEqualTo(2);
        assertThat(parsed.cases().get(0).signal().validCount()).isEqualTo(3);
    }

    @Test
    void runStoresResultAndEditClearsIt() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE)).andExpect(status().isOk());
        String id = JsonPath.read(upload(op, AdminRole.OPERATOR, dataset("run-" + rand(), oneCase("c1")), 201), "$.id");

        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + id + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backtestResult.caseCount").value(1))
                .andExpect(jsonPath("$.backtestResult.datasetId").value(id))
                .andExpect(jsonPath("$.backtestResult.report.rows[0].draft.explain").value(startsWith("T ")));

        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                        .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE.replace("\"targetFloor\":8", "\"targetFloor\":9")))
                .andExpect(jsonPath("$.backtestResult").value(nullValue()));
    }

    @Test
    void auditorIsReadOnly() throws Exception {
        UUID auditor = fx.admin("AUDITOR");
        mvc.perform(get("/admin/params/backtest-datasets").with(asAdmin(auditor, AdminRole.AUDITOR))).andExpect(status().isOk());
        mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(auditor, AdminRole.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.formatVersion").value(1));
        upload(auditor, AdminRole.AUDITOR, dataset("a-" + rand(), oneCase("c1")), 403);
        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(auditor, AdminRole.AUDITOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void approvalSummaryShowsBacktest() throws Exception {
        UUID op = fx.admin("OPERATOR");
        mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE)).andExpect(status().isOk());
        String example = mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(op, AdminRole.OPERATOR)))
                .andReturn().getResponse().getContentAsString();
        String res = mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content(example)).andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(res, "$.id");   // 이미 올라가 있으면 200 + 같은 id
        mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"datasetId\":\"" + id + "\"}")).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
        mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"보정\"}")).andExpect(status().isOk());

        UUID approvalId = jdbc.queryForObject("SELECT approval_id FROM parameter_drafts WHERE status = 'REVIEW'", UUID.class);
        String summary = paramApplyExecutor.describe(approvals.findById(approvalId).orElseThrow());
        assertThat(summary)
                .contains("데이터셋 '합성 시나리오 v1'(12건)")
                .contains("정밀도 0.56→1.00")
                .contains("재현율 0.83→1.00")
                .contains("판정 변경 10건");
    }
}
```

`ParamApplyEndToEndTest.java`(완료 기준 경로, 테스트 16):

```java
package kr.trendstage.params;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.judge.VerdictEvidence;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 완료 기준(스펙 §12-2): 업로드 → 백테스트 → 시뮬레이션 → 승인 요청 → 다른 ADMIN 승인 → 다음 판정이 새 파라미터로.
 * 적용된 드래프트는 전역 운영값을 바꾸므로 finally에서 반드시 지운다. 판정 항목은 2036년 전용.
 */
class ParamApplyEndToEndTest extends AbstractIntegrationTest {

    @Autowired JudgeService judge;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    @AfterEach
    void clean() {
        fx.clearActiveDrafts();
    }

    @Test
    void approvedDraftDrivesNextVerdict() throws Exception {
        UUID op = fx.admin("OPERATOR");
        UUID approver = fx.admin();
        UUID draftId = null;
        try {
            String draft = mvc.perform(put("/admin/params/draft").with(asAdmin(op, AdminRole.OPERATOR))
                            .contentType(MediaType.APPLICATION_JSON).content(ParamStudioDraftTest.EXAMPLE))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            draftId = UUID.fromString(JsonPath.read(draft, "$.draftId"));
            String example = mvc.perform(get("/admin/params/backtest-datasets/example").with(asAdmin(op, AdminRole.OPERATOR)))
                    .andReturn().getResponse().getContentAsString();
            String dataset = mvc.perform(post("/admin/params/backtest-datasets").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON).content(example)).andReturn().getResponse().getContentAsString();
            mvc.perform(post("/admin/params/draft/backtest").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"datasetId\":\"" + JsonPath.read(dataset, "$.id") + "\"}")).andExpect(status().isOk());
            mvc.perform(post("/admin/params/draft/simulate").with(asAdmin(op, AdminRole.OPERATOR))).andExpect(status().isOk());
            mvc.perform(post("/admin/params/draft/request-approval").with(asAdmin(op, AdminRole.OPERATOR))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"백테스트 보정\"}")).andExpect(status().isOk());
            UUID approvalId = jdbc.queryForObject("SELECT approval_id FROM parameter_drafts WHERE id = ?", UUID.class, draftId);
            mvc.perform(post("/admin/approvals/{id}/approve", approvalId).with(asAdmin(approver, AdminRole.ADMIN)))
                    .andExpect(status().isOk());
            assertThat(jdbc.queryForObject("SELECT status::text FROM parameter_drafts WHERE id = ?", String.class, draftId))
                    .isEqualTo("APPLIED");

            // 새 파라미터로 판정: 4명·4일·3곳, 활성 4명 → 목표치 max(8, ⌈0.4⌉) = 8 → T 0.5 → HIT L2 (기본값이면 0.2 → L1)
            Instant first = Instant.parse("2036-03-01T00:00:00Z");
            UUID item = fx.item(first);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-01T03:00:00Z"), "https://gall.dcinside.com/board/1", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-03T03:00:00Z"), "https://x.com/a/status/1", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-05T03:00:00Z"), "https://www.instagram.com/p/a/", null, null);
            fx.submissionFrom(fx.user(), item, Instant.parse("2036-03-08T03:00:00Z"), "https://gall.dcinside.com/board/2", null, null);
            Instant judgedAt = first.plus(Duration.ofDays(15));
            judge.closeDue(judgedAt);
            judge.judge(item, judgedAt);

            VerdictEvidence ev = objectMapper.readValue(fx.evidenceJson(item), VerdictEvidence.class);
            assertThat(ev.result()).isEqualTo("HIT");
            assertThat(ev.reach()).isEqualTo("L2");
            assertThat(ev.params().axes().persistenceFloor()).isEqualTo(0.5);
            assertThat(ev.tBreakdown().target()).isEqualTo(8);
            assertThat(ev.tBreakdown().t()).isEqualTo(0.5);
        } finally {
            if (draftId != null) fx.deleteDraft(draftId);
        }
    }
}
```

`com.jayway.jsonpath.JsonPath`는 `spring-boot-starter-test`에 들어 있다.

- [ ] **Step 3: 실패 확인**

Run: `<태스크>` = `:app:test --tests "kr.trendstage.params.*"`
Expected: 컴파일 실패(`BacktestDatasetParser` 없음)

- [ ] **Step 4: 엔티티·저장소**

`BacktestDataset.java`:

```java
package kr.trendstage.persistence.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/** ADM-600 백테스트 사례 파일(SP4 S11). 불변 — DB 트리거가 UPDATE/DELETE를 막는다. */
@Entity
@Immutable
@Table(name = "backtest_datasets")
public class BacktestDataset {

    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 64, unique = true)
    private String sha256;

    @Column(name = "case_count", nullable = false)
    private int caseCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected BacktestDataset() {}

    public BacktestDataset(String name, String sha256, int caseCount, String payload, UUID uploadedBy, Instant createdAt) {
        this.name = name;
        this.sha256 = sha256;
        this.caseCount = caseCount;
        this.payload = payload;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getSha256() { return sha256; }
    public int getCaseCount() { return caseCount; }
    public String getPayload() { return payload; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
}
```

`BacktestDatasetRepository.java`:

```java
package kr.trendstage.persistence.repo;

import kr.trendstage.persistence.entity.BacktestDataset;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BacktestDatasetRepository extends JpaRepository<BacktestDataset, UUID> {
    Optional<BacktestDataset> findBySha256(String sha256);

    List<BacktestDataset> findAllByOrderByCreatedAtDesc();
}
```

- [ ] **Step 5: 파서**

```java
package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.AdminValidationException;
import kr.trendstage.domain.backtest.BacktestCase;
import kr.trendstage.domain.signal.PlatformResolver;
import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.TrendSignal;
import kr.trendstage.domain.verdict.VerdictResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 백테스트 사례 파일(formatVersion 1, SP4 §5.2) → 사례 목록. 운영 판정과 같은 입력 형태(TrendSignal)로 바꾼다:
 * 이름표 → 결정적 UUID, 근거 링크 → PlatformResolver, device·ip 이름표 → 사례 안 그룹 번호(제출 순, §4와 같은 방식).
 * 위반은 AdminValidationException("경로: 메시지") — 경로는 cases[3].submissions[5].at 형태.
 */
@Component
public class BacktestDatasetParser {

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_CASES = 500;

    private final ObjectMapper objectMapper;

    public BacktestDatasetParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public record Parsed(String name, List<BacktestCase> cases) {}

    private record Raw(int index, String submitter, Instant at, String evidenceUrl, String device, String ip,
                       boolean seed, Instant joinedAt) {}

    public Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) throw new AdminValidationException("사례 파일이 비어 있습니다");
        if (raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new AdminValidationException("사례 파일은 2MB 이하여야 합니다");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new AdminValidationException("JSON 형식이 아닙니다: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) throw new AdminValidationException("최상위는 JSON 객체여야 합니다");
        if (root.path("formatVersion").asInt(-1) != 1) throw invalid("formatVersion", "1이어야 합니다");
        String name = text(root, "name", "name", 120);
        JsonNode cases = root.path("cases");
        if (!cases.isArray() || cases.isEmpty()) throw invalid("cases", "사례가 1건 이상 필요합니다");
        if (cases.size() > MAX_CASES) {
            throw invalid("cases", MAX_CASES + "건 이하여야 합니다 (입력 " + cases.size() + "건)");
        }
        Set<String> ids = new HashSet<>();
        List<BacktestCase> out = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            out.add(parseCase(cases.get(i), "cases[" + i + "]", ids));
        }
        return new Parsed(name, out);
    }

    private BacktestCase parseCase(JsonNode c, String at, Set<String> ids) {
        if (!c.isObject()) throw invalid(at, "객체여야 합니다");
        String caseId = text(c, "caseId", at + ".caseId", 60);
        if (!ids.add(caseId)) throw invalid(at + ".caseId", "중복입니다: " + caseId);
        String title = text(c, "title", at + ".title", 200);
        VerdictResult label = switch (text(c, "label", at + ".label", 10)) {
            case "HIT" -> VerdictResult.HIT;
            case "MISS" -> VerdictResult.MISS;
            default -> throw invalid(at + ".label", "HIT 또는 MISS여야 합니다");
        };
        ReachLevel labelReach = null;
        if (c.hasNonNull("labelReach")) {
            if (label != VerdictResult.HIT) throw invalid(at + ".labelReach", "label이 HIT일 때만 쓸 수 있습니다");
            try {
                labelReach = ReachLevel.valueOf(c.get("labelReach").asText());
            } catch (IllegalArgumentException e) {
                throw invalid(at + ".labelReach", "L1 ~ L4 중 하나여야 합니다");
            }
        }
        Instant deadline = instant(c.get("deadline"), at + ".deadline");
        Integer active = null;
        if (c.hasNonNull("activeSubmitters")) {
            JsonNode a = c.get("activeSubmitters");
            if (!a.isIntegralNumber() || !a.canConvertToInt() || a.asInt() < 0) {
                throw invalid(at + ".activeSubmitters", "0 이상의 정수여야 합니다");
            }
            active = a.asInt();
        }
        JsonNode subs = c.path("submissions");
        if (!subs.isArray() || subs.isEmpty()) throw invalid(at + ".submissions", "제보가 1건 이상 필요합니다");

        List<Raw> raws = new ArrayList<>();
        for (int j = 0; j < subs.size(); j++) {
            JsonNode s = subs.get(j);
            String p = at + ".submissions[" + j + "]";
            if (!s.isObject()) throw invalid(p, "객체여야 합니다");
            Instant when = instant(s.get("at"), p + ".at");
            if (!when.isBefore(deadline)) throw invalid(p + ".at", "deadline보다 앞서야 합니다");
            JsonNode seed = s.get("seed");
            if (seed != null && !seed.isNull() && !seed.isBoolean()) throw invalid(p + ".seed", "true 또는 false여야 합니다");
            raws.add(new Raw(j, text(s, "submitter", p + ".submitter", 60), when,
                    text(s, "evidenceUrl", p + ".evidenceUrl", 2000),
                    optionalText(s, "device", p + ".device"), optionalText(s, "ip", p + ".ip"),
                    seed != null && seed.asBoolean(false),
                    s.hasNonNull("joinedAt") ? instant(s.get("joinedAt"), p + ".joinedAt") : null));
        }
        raws.sort(Comparator.comparing(Raw::at).thenComparingInt(Raw::index));

        Map<String, Integer> devices = new HashMap<>();
        Map<String, Integer> ips = new HashMap<>();
        List<TrendSignal.Entry> entries = new ArrayList<>();
        for (Raw r : raws) {
            entries.add(new TrendSignal.Entry(
                    uuid(caseId, "submission", String.valueOf(r.index())), uuid(caseId, "submitter", r.submitter()),
                    r.seed(), r.at(), null, r.joinedAt(), PlatformResolver.resolve(r.evidenceUrl()).name(),
                    group(devices, r.device()), group(ips, r.ip())));
        }
        return new BacktestCase(caseId, title, label, labelReach, new TrendSignal(deadline, entries, active));
    }

    private static UUID uuid(String caseId, String kind, String key) {
        return UUID.nameUUIDFromBytes(("backtest:" + caseId + ":" + kind + ":" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static Integer group(Map<String, Integer> groups, String label) {
        return label == null ? null : groups.computeIfAbsent(label, k -> groups.size() + 1);
    }

    private static String text(JsonNode node, String field, String path, int maxLength) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isTextual() || v.asText().isBlank()) throw invalid(path, "문자열 값이 필요합니다");
        String s = v.asText().trim();
        if (s.length() > maxLength) throw invalid(path, maxLength + "자 이하여야 합니다");
        return s;
    }

    private static String optionalText(JsonNode node, String field, String path) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        if (!v.isTextual()) throw invalid(path, "문자열이어야 합니다");
        String s = v.asText().trim();
        if (s.length() > 60) throw invalid(path, "60자 이하여야 합니다");
        return s.isEmpty() ? null : s;
    }

    private static Instant instant(JsonNode v, String path) {
        if (v == null || v.isNull() || !v.isTextual()) throw invalid(path, "시각이 필요합니다 (예: 2026-06-01T03:00:00Z)");
        try {
            return Instant.parse(v.asText());
        } catch (DateTimeParseException e) {
            throw invalid(path, "ISO-8601 UTC 시각이어야 합니다 (예: 2026-06-01T03:00:00Z)");
        }
    }

    private static AdminValidationException invalid(String path, String message) {
        return new AdminValidationException(path + ": " + message);
    }
}
```

- [ ] **Step 6: 서비스·컨트롤러·승인 요약**

`BacktestService.java`:

```java
package kr.trendstage.apiadmin.params;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.trendstage.apiadmin.auth.DraftLockedException;
import kr.trendstage.apiadmin.web.AdminConflictException;
import kr.trendstage.apiadmin.web.AdminNotFoundException;
import kr.trendstage.audit.AuditLogService;
import kr.trendstage.domain.backtest.Backtest;
import kr.trendstage.domain.backtest.BacktestReport;
import kr.trendstage.persistence.entity.BacktestDataset;
import kr.trendstage.persistence.entity.ParameterDraft;
import kr.trendstage.persistence.repo.BacktestDatasetRepository;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.ParamStatus;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ADM-600 백테스트(SP4 S9·S11). 사례 파일은 바꿀 수 없게 보관하고(같은 SHA-256이면 기존 행), 실행 결과는
 * 데이터셋 해시와 함께 드래프트에 남긴다 — 승인 요청의 근거.
 */
@Service
public class BacktestService {

    static final String EXAMPLE = "backtest/synthetic-v1.json";

    private final BacktestDatasetRepository datasets;
    private final BacktestDatasetParser parser;
    private final ParamStudioService studio;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public BacktestService(BacktestDatasetRepository datasets, BacktestDatasetParser parser, ParamStudioService studio,
                           AuditLogService auditLogService, ObjectMapper objectMapper, Clock clock) {
        this.datasets = datasets;
        this.parser = parser;
        this.studio = studio;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record Upload(BacktestDataset dataset, boolean created) {}

    /** 드래프트에 남기는 결과(§5.4). */
    public record BacktestResult(UUID datasetId, String datasetName, String sha256, int caseCount, Instant ranAt,
                                 BacktestReport report) {}

    @Transactional
    public Upload upload(UUID actorId, AdminRole actorRole, String raw) {
        BacktestDatasetParser.Parsed parsed = parser.parse(raw);
        String sha = sha256(raw);
        Optional<BacktestDataset> existing = datasets.findBySha256(sha);
        if (existing.isPresent()) return new Upload(existing.get(), false);
        BacktestDataset saved;
        try {
            saved = datasets.saveAndFlush(new BacktestDataset(parsed.name(), sha, parsed.cases().size(), raw,
                    actorId, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new AdminConflictException("dataset-upload-race", "같은 파일이 동시에 올라왔습니다 — 목록을 새로고침하세요");
        }
        auditLogService.record(actorId, actorRole, "BACKTEST_DATASET_UPLOAD", "BACKTEST_DATASET", saved.getId(), Map.of(
                "name", parsed.name(), "caseCount", parsed.cases().size(), "sha256", sha));
        return new Upload(saved, true);
    }

    @Transactional(readOnly = true)
    public List<BacktestDataset> list() {
        return datasets.findAllByOrderByCreatedAtDesc();
    }

    public String example() {
        try {
            return new ClassPathResource(EXAMPLE).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Transactional
    public ParameterDraft run(UUID actorId, AdminRole actorRole, UUID datasetId) {
        BacktestDataset ds = datasets.findById(datasetId)
                .orElseThrow(() -> new AdminNotFoundException("데이터셋이 없습니다: " + datasetId));
        ParameterDraft draft = studio.getOrCreateActiveDraft(actorId);
        if (draft.getStatus() == ParamStatus.REVIEW) {
            throw new DraftLockedException("승인 대기 중인 드래프트에는 백테스트를 다시 돌릴 수 없습니다");
        }
        BacktestReport report = Backtest.run(parser.parse(ds.getPayload()).cases(),
                studio.currentOperationalParams(), draft.toParameterSet(objectMapper));
        try {
            draft.recordBacktestResult(objectMapper.writeValueAsString(new BacktestResult(
                    ds.getId(), ds.getName(), ds.getSha256(), ds.getCaseCount(), clock.instant(), report)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("백테스트 결과 직렬화 실패", e);
        }
        auditLogService.record(actorId, actorRole, "PARAM_BACKTEST", "PARAMETER_DRAFT", draft.getId(), Map.of(
                "datasetId", ds.getId().toString(), "caseCount", ds.getCaseCount(), "changed", report.changedCount()));
        return draft;
    }

    static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
```

예외 위치(확인됨): `apiadmin.auth.{AdminValidationException, DraftLockedException}`, `apiadmin.web.{AdminConflictException(String type, String message), AdminNotFoundException}` — 409·404·422 매핑은 `AdminApiExceptionHandler`에 이미 있다.

`BacktestController.java`:

```java
package kr.trendstage.apiadmin.web;

import kr.trendstage.apiadmin.auth.AdminPrincipal;
import kr.trendstage.apiadmin.params.BacktestService;
import kr.trendstage.persistence.entity.AdminAccount;
import kr.trendstage.persistence.entity.BacktestDataset;
import kr.trendstage.persistence.repo.AdminAccountRepository;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** ADM-600 백테스트 데이터셋(SP4 §5.3). 업로드는 O/A, 조회·예시는 O/A/Au. 실행은 ParamStudioController. */
@RestController
@RequestMapping("/admin/params/backtest-datasets")
public class BacktestController {

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Seoul"));

    private final BacktestService service;
    private final AdminAccountRepository accounts;

    public BacktestController(BacktestService service, AdminAccountRepository accounts) {
        this.service = service;
        this.accounts = accounts;
    }

    public record DatasetSummary(String id, String name, int caseCount, String sha256, String uploadedBy, String createdAt) {}

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ResponseEntity<DatasetSummary> upload(@RequestBody String body, @AuthenticationPrincipal AdminPrincipal actor) {
        BacktestService.Upload up = service.upload(actor.id(), actor.role(), body);
        return ResponseEntity.status(up.created() ? 201 : 200).body(summary(up.dataset()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public List<DatasetSummary> list() {
        return service.list().stream().map(this::summary).toList();
    }

    @GetMapping(value = "/example", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN', 'AUDITOR')")
    public String example() {
        return service.example();
    }

    private DatasetSummary summary(BacktestDataset d) {
        String by = accounts.findById(d.getUploadedBy()).map(AdminAccount::getDisplayName).orElse("-");
        return new DatasetSummary(d.getId().toString(), d.getName(), d.getCaseCount(), d.getSha256(), by,
                KST.format(d.getCreatedAt()));
    }
}
```

`ParamStudioController`: 생성자에 `BacktestService backtestService`를 받고 추가:

```java
    public record BacktestRunRequest(String datasetId) {}

    @PostMapping("/draft/backtest")
    @PreAuthorize("hasAnyRole('OPERATOR', 'ADMIN')")
    public ParameterDraftResponse backtest(@RequestBody BacktestRunRequest req, @AuthenticationPrincipal AdminPrincipal actor) {
        UUID datasetId;
        try {
            datasetId = UUID.fromString(req.datasetId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new AdminValidationException("datasetId: UUID가 필요합니다");
        }
        return toResponse(backtestService.run(actor.id(), actor.role(), datasetId));
    }
```

`ParamApplyExecutor`: 생성자에 `ObjectMapper objectMapper`를 받고 `describe`를 바꾼다(import `com.fasterxml.jackson.databind.JsonNode`, `java.util.Locale`):

```java
    @Override
    public String describe(ApprovalRequest request) {
        String base = "파라미터 적용 · 드래프트 " + request.getTargetRef().toString().substring(0, 8);
        return drafts.findById(request.getTargetRef())
                .map(ParameterDraft::getBacktestResult)
                .map(json -> base + " · " + backtestSummary(json))
                .orElse(base);
    }

    /** 승인자가 볼 근거(SP4 §5.3): "데이터셋 '이름'(N건) — 정밀도 a→b, 재현율 c→d, 판정 변경 k건". */
    private String backtestSummary(String json) {
        try {
            JsonNode r = objectMapper.readTree(json);
            JsonNode report = r.path("report");
            return "데이터셋 '%s'(%d건) — 정밀도 %s→%s, 재현율 %s→%s, 판정 변경 %d건".formatted(
                    r.path("datasetName").asText(), r.path("caseCount").asInt(),
                    ratio(report.path("current").path("precision")), ratio(report.path("draft").path("precision")),
                    ratio(report.path("current").path("recall")), ratio(report.path("draft").path("recall")),
                    report.path("changedCount").asInt());
        } catch (Exception e) {
            return "백테스트 결과를 읽지 못했습니다";
        }
    }

    private static String ratio(JsonNode n) {
        return n.isNumber() ? String.format(Locale.ROOT, "%.2f", n.asDouble()) : "—";
    }
```

- [ ] **Step 7: 통과 확인**

Run: `<태스크>` = `compileJava compileTestJava :app:test --tests "kr.trendstage.params.*" --tests "kr.trendstage.approval.*" --tests "kr.trendstage.signal.*"`
Expected: PASS

- [ ] **Step 8: 커밋**

```bash
git add backend/persistence/src/main/java/kr/trendstage/persistence/entity/BacktestDataset.java backend/persistence/src/main/java/kr/trendstage/persistence/repo/BacktestDatasetRepository.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/BacktestDatasetParser.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/params/BacktestService.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/BacktestController.java backend/api-admin/src/main/resources/backtest/synthetic-v1.json backend/api-admin/src/main/java/kr/trendstage/apiadmin/web/ParamStudioController.java backend/api-admin/src/main/java/kr/trendstage/apiadmin/approval/ParamApplyExecutor.java backend/app/src/test/java/kr/trendstage/params/BacktestApiTest.java backend/app/src/test/java/kr/trendstage/params/SyntheticScenarioTest.java backend/app/src/test/java/kr/trendstage/params/ParamApplyEndToEndTest.java
git commit -m "feat(backtest): 사례 파일 불변 보관·검증(위치 표시), 현재 vs 초안 백테스트, 합성 시나리오 12건, 승인 요약에 정밀도·재현율(SP4 §5.2~§5.5)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 9: API 계약(`openapi.yaml`)

**Files:**
- Modify: `backend/api-spec/openapi.yaml`

**Interfaces:**
- Consumes: Task 4·5·6·8의 실제 요청·응답 필드
- Produces: 실제 컨트롤러와 필드 단위로 같은 계약

- [ ] **Step 1: 파라미터 스튜디오 경로 교체**

`/admin/parameter-drafts`, `/admin/parameter-drafts/{id}/simulate`, `/admin/parameter-drafts/{id}/request-approval` 세 경로(지금은 실제 컨트롤러와 다른 옛 설계)를 지우고 아래로 바꾼다. 같은 자리(`# 콘솔 · 거버넌스` 아래)에 둔다.

```yaml
  /admin/params/draft:
    get:
      tags: [admin-governance]
      summary: 활성 드래프트 (ADM-600) — 없으면 만든다(O/A). AUDITOR는 만들지 않고 운영값만(status NONE)
      security: [{ cookieAuth: [] }]
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ParameterDraftView' } } } }
    put:
      tags: [admin-governance]
      summary: 드래프트 값 9개 저장 — 시뮬레이션·백테스트 결과를 지운다 (O/A)
      security: [{ cookieAuth: [] }]
      requestBody:
        required: true
        content: { application/json: { schema: { $ref: '#/components/schemas/DraftValues' } } }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ParameterDraftView' } } } }
        '409': { description: 승인 대기 중인 드래프트 }
        '422': { description: "필드 누락·범위 위반 — detail이 필드명으로 시작 (예: \"persistenceFloor: 0 ~ 1 사이여야 합니다 (입력 1.5)\")" }
  /admin/params/draft/simulate:
    post:
      tags: [admin-governance]
      summary: 시뮬레이션 — 최근 180일 판정을 동결된 신호로 재계산 (O/A)
      security: [{ cookieAuth: [] }]
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ParameterDraftView' } } } }
  /admin/params/draft/backtest:
    post:
      tags: [admin-governance]
      summary: 백테스트 — 데이터셋을 현재 운영값·초안값으로 판정해 정답과 비교 (O/A, SP4 S9)
      security: [{ cookieAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [datasetId]
              properties:
                datasetId: { type: string, format: uuid }
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/ParameterDraftView' } } } }
        '404': { description: 데이터셋 없음 }
        '409': { description: 승인 대기 중인 드래프트 }
        '422': { description: datasetId 형식 오류 }
  /admin/params/draft/request-approval:
    post:
      tags: [admin-governance]
      summary: 적용 승인 요청 — 마지막 수정 이후 시뮬레이션과 백테스트가 둘 다 있어야 한다 (O/A). 승인되면 즉시 운영값, 이후 판정부터(비소급)
      security: [{ cookieAuth: [] }]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [reason]
              properties:
                reason: { type: string }
      responses:
        '200': { description: 승인 대기(REVIEW), content: { application/json: { schema: { $ref: '#/components/schemas/ParameterDraftView' } } } }
        '409': { description: 이미 승인 대기 중 }
        '422': { description: 사유 누락, 시뮬레이션 또는 백테스트 미실행 }
  /admin/params/backtest-datasets:
    get:
      tags: [admin-governance]
      summary: 백테스트 데이터셋 목록 (O/A/Au) — 본문 제외
      security: [{ cookieAuth: [] }]
      responses:
        '200':
          description: OK
          content: { application/json: { schema: { type: array, items: { $ref: '#/components/schemas/BacktestDatasetSummary' } } } }
    post:
      tags: [admin-governance]
      summary: 사례 파일 업로드 (O/A) — 불변 보관, 같은 SHA-256이면 기존 행
      description: >
        형식은 스펙 2026-09-24 §5.2(formatVersion 1). label은 평가용 정답이지 판정 입력이 아니다(R5·S10).
        파일 2MB·사례 500건 이하.
      security: [{ cookieAuth: [] }]
      requestBody:
        required: true
        content: { application/json: { schema: { $ref: '#/components/schemas/BacktestDatasetFile' } } }
      responses:
        '200': { description: 이미 있는 파일, content: { application/json: { schema: { $ref: '#/components/schemas/BacktestDatasetSummary' } } } }
        '201': { description: 새로 보관됨, content: { application/json: { schema: { $ref: '#/components/schemas/BacktestDatasetSummary' } } } }
        '409': { description: 같은 파일이 동시에 올라옴 }
        '422': { description: "형식 위반 — detail은 \"cases[3].submissions[5].at: deadline보다 앞서야 합니다\" 형태" }
  /admin/params/backtest-datasets/example:
    get:
      tags: [admin-governance]
      summary: 합성 시나리오 12건(형식 견본) (O/A/Au)
      security: [{ cookieAuth: [] }]
      responses:
        '200': { description: OK, content: { application/json: { schema: { $ref: '#/components/schemas/BacktestDatasetFile' } } } }
```

- [ ] **Step 2: 스키마 교체·추가**

`ParameterPayload`·`ParameterDraft`·`SimulationResult`(다른 곳에서 `$ref`하지 않는지 `grep -n "ParameterPayload\|schemas/ParameterDraft'\|SimulationResult"`로 확인 후)를 지우고 추가:

```yaml
    IndependenceMode:
      type: string
      enum: [OFF, DEVICE, DEVICE_OR_IP]
      description: 독립성 압축 — DEVICE_OR_IP는 공유 IP 오탐 위험(실사례 백테스트 전엔 끔)
    DraftValues:
      type: object
      description: ADM-600 편집 값 9개(SP4 §5.1). 기본값은 SP4 이전 판정과 같다(중립).
      required: [targetFloor, targetRatio, activeWindowDays, hitThreshold, persistenceFloor, persistenceFullDays, diversityFloor, diversityFullPlatforms, independenceMode]
      properties:
        targetFloor: { type: integer, minimum: 1, description: "목표치 하한(구 submitterTarget). 기본 20" }
        targetRatio: { type: number, minimum: 0, maximum: 1, description: "목표치 = max(하한, ⌈활성 제보자 × 비율⌉). 기본 0" }
        activeWindowDays: { type: integer, minimum: 7, maximum: 90, description: "활성 제보자를 세는 기간(관측 마감 직전). 기본 28" }
        hitThreshold: { type: number, minimum: 0, maximum: 1, description: "기본 0.20" }
        persistenceFloor: { type: number, minimum: 0, maximum: 1, description: "지속성 계수 하한. 1이면 꺼짐(기본)" }
        persistenceFullDays: { type: integer, minimum: 1, maximum: 14, description: "지속성 1.0이 되는 제보일수(KST). 기본 5" }
        diversityFloor: { type: number, minimum: 0, maximum: 1, description: "다양성 계수 하한. 1이면 꺼짐(기본)" }
        diversityFullPlatforms: { type: integer, minimum: 2, maximum: 11, description: "다양성 1.0이 되는 플랫폼 수. 기본 3" }
        independenceMode: { $ref: '#/components/schemas/IndependenceMode' }
    SimulationSummary:
      type: object
      properties:
        changed: { type: integer }
        total: { type: integer }
        missToHit: { type: integer }
        hitToMiss: { type: integer }
        reachChanged: { type: integer }
    ParameterDraftView:
      type: object
      properties:
        draftId: { type: [string, 'null'], format: uuid }
        status: { type: string, enum: [DRAFT, REVIEW, NONE] }
        values: { $ref: '#/components/schemas/DraftValues' }
        current: { $ref: '#/components/schemas/DraftValues' }
        simResult:
          oneOf: [{ $ref: '#/components/schemas/SimulationSummary' }, { type: 'null' }]
        backtestResult:
          oneOf: [{ $ref: '#/components/schemas/BacktestResult' }, { type: 'null' }]
    TBreakdown:
      type: object
      description: 유효 T 분해(SP4 §2) — T = ratio × persistence × diversity
      properties:
        accounts: { type: integer }
        independent: { type: integer }
        target: { type: integer }
        activeSubmitters: { type: [integer, 'null'] }
        ratio: { type: number }
        activeDays: { type: integer }
        persistenceFullDays: { type: integer }
        persistence: { type: number }
        platforms: { type: integer }
        diversityFullPlatforms: { type: integer }
        diversityApplied: { type: boolean, description: false면 플랫폼 코드 없는 제보(SP4 이전)가 섞여 다양성 1 }
        diversity: { type: number }
        t: { type: number }
    BacktestOutcome:
      type: object
      properties:
        result: { type: string, enum: [HIT, MISS] }
        reach: { type: [string, 'null'] }
        breakdown: { $ref: '#/components/schemas/TBreakdown' }
        explain: { type: string, example: "T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일/5일) × 다양성 0.80 (2곳/3곳)" }
    BacktestSide:
      type: object
      properties:
        confusion:
          type: object
          properties: { tp: { type: integer }, fp: { type: integer }, fn: { type: integer }, tn: { type: integer } }
        precision: { type: [number, 'null'], description: TP/(TP+FP), 분모 0이면 null }
        recall: { type: [number, 'null'], description: TP/(TP+FN) }
        reachAgreement: { type: [number, 'null'] }
        reachCompared: { type: integer }
    BacktestResult:
      type: object
      properties:
        datasetId: { type: string, format: uuid }
        datasetName: { type: string }
        sha256: { type: string }
        caseCount: { type: integer }
        ranAt: { type: string, format: date-time }
        report:
          type: object
          properties:
            caseCount: { type: integer }
            current: { $ref: '#/components/schemas/BacktestSide' }
            draft: { $ref: '#/components/schemas/BacktestSide' }
            changedCount: { type: integer }
            rows:
              type: array
              items:
                type: object
                properties:
                  caseId: { type: string }
                  title: { type: string }
                  label: { type: string, enum: [HIT, MISS] }
                  labelReach: { type: [string, 'null'] }
                  current: { $ref: '#/components/schemas/BacktestOutcome' }
                  draft: { $ref: '#/components/schemas/BacktestOutcome' }
                  changed: { type: boolean }
    BacktestDatasetSummary:
      type: object
      properties:
        id: { type: string, format: uuid }
        name: { type: string }
        caseCount: { type: integer }
        sha256: { type: string }
        uploadedBy: { type: string, description: 표시 이름 }
        createdAt: { type: string, description: KST yyyy-MM-dd HH:mm }
    BacktestDatasetFile:
      type: object
      required: [formatVersion, name, cases]
      properties:
        formatVersion: { type: integer, enum: [1] }
        name: { type: string, maxLength: 120 }
        cases:
          type: array
          minItems: 1
          maxItems: 500
          items:
            type: object
            required: [caseId, title, label, deadline, submissions]
            properties:
              caseId: { type: string, maxLength: 60 }
              title: { type: string, maxLength: 200 }
              label: { type: string, enum: [HIT, MISS], description: 사후 정답 — 평가용, 판정 입력 아님 }
              labelReach: { type: string, enum: [L1, L2, L3, L4], description: label이 HIT일 때만 }
              note: { type: string }
              deadline: { type: string, format: date-time }
              activeSubmitters: { type: integer, minimum: 0, description: 없으면 목표치 = 하한 }
              submissions:
                type: array
                minItems: 1
                items:
                  type: object
                  required: [submitter, at, evidenceUrl]
                  properties:
                    submitter: { type: string, description: 이름표 — 같은 값 = 같은 사람 }
                    at: { type: string, format: date-time, description: deadline보다 앞서야 한다 }
                    evidenceUrl: { type: string, description: 플랫폼 판별에 쓴다 }
                    device: { type: string, description: 이름표 — 같은 값 = 같은 기기 }
                    ip: { type: string, description: 이름표 — 같은 값 = 같은 IP }
                    seed: { type: boolean, default: false }
                    joinedAt: { type: string, format: date-time }
```

- [ ] **Step 3: 제보·판정 계약**

`/v1/submissions` `post`에 헤더 파라미터와 422를 추가:

```yaml
      parameters:
        - name: X-Device-Id
          in: header
          required: false
          schema: { type: string, maxLength: 128 }
          description: 기기 ID(안드로이드 ID·iOS 제조사별 ID·웹 설치별 무작위 ID). 서버는 HMAC 해시만 저장한다(SP4 §4). 없어도 제보는 된다.
```
(`responses`에 `'422': { description: "confidence가 10/30/50이 아니거나 X-Device-Id가 128자 초과" }`가 없으면 추가)

`SubmissionCreate`: `required`에서 `platform`을 빼고 `platform: { type: string, maxLength: 60, description: "옛 앱 빌드 호환 — 보관만 하고 판정·표시에 쓰지 않는다. 플랫폼은 evidenceUrl 도메인으로 서버가 판별(SP4 S4·S12)" }`, `evidenceUrl` 설명에 "플랫폼 판별에 쓴다"를 더한다.

`VerdictJudgedItem`에 `tExplain: { type: [string, 'null'], description: "산정 근거 — T 분해 문자열. SP4 이전 판정·VOID는 null" }`.

(관리자 항목 상세 `/admin/trends/{id}`의 스키마에는 예상 판정 필드가 없다 — `previewExplain`은 openapi에 추가하지 않는다. 계약 정비는 이 태스크 범위 밖.)

- [ ] **Step 4: 검증**

Run(저장소 루트):
```bash
MSYS_NO_PATHCONV=1 docker run --rm -v "$(cygpath -w "$PWD/backend/api-spec"):/spec:ro" node:20 npx -y @apidevtools/swagger-cli validate /spec/openapi.yaml
```
Expected: `/spec/openapi.yaml is valid`

- [ ] **Step 5: 커밋**

```bash
git add backend/api-spec/openapi.yaml
git commit -m "docs(api): 파라미터 스튜디오·백테스트 엔드포인트를 실제 계약으로, 제보 X-Device-Id·platform 선택화, 판정 T 분해(SP4)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 10: 관리자 콘솔

**Files:**
- Create: `admin/src/platform.ts`
- Modify: `admin/src/api/{client,types,hooks}.ts`, `admin/src/fixtures.ts`
- Modify: `admin/src/screens/{ParamStudioScreen,VerdictScreen,TrendDetailScreen,SeedScreen}.tsx`

**Interfaces:**
- Consumes: Task 6·8의 `ParameterDraftView`(values/current/simResult/backtestResult), 데이터셋 API; Task 5의 `tExplain`·`previewExplain`
- Produces: 화면

- [ ] **Step 1: API 층**

`client.ts` — 파일을 바이트 그대로 보내는 `postRaw`(같은 파일이면 같은 SHA-256이 되게):

```ts
/** 이미 JSON 문자열인 본문 — 다시 stringify하지 않는다(사례 파일은 바이트 그대로 보내야 같은 파일이 같은 해시가 된다). */
class RawJson {
  constructor(public text: string) {}
}
```
`request`의 `body:` 줄을 `body: body === undefined ? undefined : body instanceof RawJson ? body.text : JSON.stringify(body),`로, `api`에 `postRaw: <T>(p: string, rawJson: string) => request<T>("POST", p, new RawJson(rawJson)),`를 추가한다.

`types.ts` — `ParameterDraftView`를 교체하고 추가:

```ts
export type IndependenceMode = "OFF" | "DEVICE" | "DEVICE_OR_IP";
export interface DraftValues {
  targetFloor: number; targetRatio: number; activeWindowDays: number; hitThreshold: number;
  persistenceFloor: number; persistenceFullDays: number; diversityFloor: number; diversityFullPlatforms: number;
  independenceMode: IndependenceMode;
}
export interface TBreakdown {
  accounts: number; independent: number; target: number; activeSubmitters: number | null; ratio: number;
  activeDays: number; persistenceFullDays: number; persistence: number;
  platforms: number; diversityFullPlatforms: number; diversityApplied: boolean; diversity: number; t: number;
}
export interface BacktestOutcome { result: "HIT" | "MISS"; reach: string | null; breakdown: TBreakdown; explain: string }
export interface BacktestSide {
  confusion: { tp: number; fp: number; fn: number; tn: number };
  precision: number | null; recall: number | null; reachAgreement: number | null; reachCompared: number;
}
export interface BacktestCaseRow {
  caseId: string; title: string; label: "HIT" | "MISS"; labelReach: string | null;
  current: BacktestOutcome; draft: BacktestOutcome; changed: boolean;
}
export interface BacktestResult {
  datasetId: string; datasetName: string; sha256: string; caseCount: number; ranAt: string;
  report: { caseCount: number; current: BacktestSide; draft: BacktestSide; changedCount: number; rows: BacktestCaseRow[] };
}
export interface BacktestDatasetSummary {
  id: string; name: string; caseCount: number; sha256: string; uploadedBy: string; createdAt: string;
}
export interface ParameterDraftView {
  draftId: string | null;
  status: "DRAFT" | "REVIEW" | "NONE";
  values: DraftValues;
  current: DraftValues;
  simResult: SimulationSummary | null;
  backtestResult: BacktestResult | null;
}
```

`JudgedItem`에 `tExplain: string | null;`, `TrendItemDetail`의 `previewScoreT` 뒤에 `previewExplain: string | null;`, `SeedSubmissionRequest`에서 `platform` 삭제.

`hooks.ts`(import 목록에 `BacktestDatasetSummary`, `DraftValues` 추가):

```ts
export const updateParamDraft = (body: DraftValues) =>
  api.put<ParameterDraftView>("/admin/params/draft", body);

export const useBacktestDatasets = () =>
  useData<BacktestDatasetSummary[]>(["admin", "backtest-datasets"], "/admin/params/backtest-datasets", fx.fxBacktestDatasets);

export const uploadBacktestDataset = (rawJson: string) =>
  api.postRaw<BacktestDatasetSummary>("/admin/params/backtest-datasets", rawJson);

export const fetchBacktestExample = () =>
  api.get<unknown>("/admin/params/backtest-datasets/example");

export const runBacktest = (datasetId: string) =>
  api.post<ParameterDraftView>("/admin/params/draft/backtest", { datasetId });
```

`fixtures.ts`:

```ts
const NEUTRAL: DraftValues = {
  targetFloor: 20, targetRatio: 0, activeWindowDays: 28, hitThreshold: 0.2,
  persistenceFloor: 1, persistenceFullDays: 5, diversityFloor: 1, diversityFullPlatforms: 3, independenceMode: "OFF",
};

export const fxParamDraft: ParameterDraftView = {
  draftId: "pd-17",
  status: "DRAFT",
  values: NEUTRAL,
  current: NEUTRAL,
  simResult: null,
  backtestResult: null,
};

export const fxBacktestDatasets: BacktestDatasetSummary[] = [];
```
(import 타입 추가)

`admin/src/platform.ts`:

```ts
/**
 * 근거 링크 → 플랫폼 라벨 미리보기. 서버 PlatformResolver(SP4 §3)의 복사본 — 서버 판별이 권위이고,
 * 어긋나면 미리보기 문구만 틀린다. 목록을 바꾸면 서버·앱(app/src/platform.ts)과 함께 바꾼다.
 */
const TABLE: [string, string[]][] = [
  ["디시", ["dcinside.com"]], ["더쿠", ["theqoo.net"]], ["에펨코리아", ["fmkorea.com"]], ["인스티즈", ["instiz.net"]],
  ["X", ["x.com", "twitter.com"]], ["인스타", ["instagram.com"]], ["스레드", ["threads.net", "threads.com"]],
  ["유튜브", ["youtube.com", "youtu.be"]], ["틱톡", ["tiktok.com"]], ["네이버", ["naver.com"]],
];

export function hostOf(url: string): string | null {
  const s = url.trim();
  const sep = s.indexOf("://");
  if (sep <= 0) return null;
  const scheme = s.slice(0, sep).toLowerCase();
  if (scheme !== "http" && scheme !== "https") return null;
  let rest = s.slice(sep + 3);
  for (const c of ["/", "?", "#", "\\"]) {
    const i = rest.indexOf(c);
    if (i >= 0) rest = rest.slice(0, i);
  }
  const at = rest.lastIndexOf("@");
  if (at >= 0) rest = rest.slice(at + 1);
  const colon = rest.indexOf(":");
  if (colon >= 0) rest = rest.slice(0, colon);
  let host = rest.toLowerCase();
  while (host.endsWith(".")) host = host.slice(0, -1);
  return host || null;
}

export function platformLabel(url: string): string {
  const host = hostOf(url);
  if (!host) return "기타";
  for (const [label, domains] of TABLE) {
    if (domains.some((d) => host === d || host.endsWith("." + d))) return label;
  }
  return "기타";
}
```

- [ ] **Step 2: `ParamStudioScreen.tsx` 교체**

```tsx
import React, { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import {
  fetchBacktestExample, requestParamApproval, runBacktest, simulateParamDraft, updateParamDraft,
  uploadBacktestDataset, useBacktestDatasets, useParamDraft,
} from "../api/hooks";
import type { BacktestCaseRow, BacktestSide, DraftValues, IndependenceMode, ParameterDraftView } from "../api/types";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

/**
 * ADM-600. 드래프트(9개 값) → 시뮬레이션 + 백테스트 → 2인 승인 → 즉시 운영값(이후 판정부터, 비소급).
 * 두 결과가 없으면 승인 요청 불가(서버가 막는다 — 버튼은 표시용).
 */
export default function ParamStudioScreen() {
  const { role } = useRole();
  const queryClient = useQueryClient();
  const { data: draft, isLoading } = useParamDraft();

  const [v, setV] = useState<DraftValues | null>(null);
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (draft) setV(draft.values);
  }, [draft?.draftId, JSON.stringify(draft?.values)]);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };
  const onError = (e: unknown) => flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");
  const put = (next: ParameterDraftView) => queryClient.setQueryData(["admin", "param-draft"], next);
  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    try { await fn(); } catch (e) { onError(e); } finally { setBusy(false); }
  };

  if (isLoading || !draft || !v) return <div style={{ padding: 24, font: "500 13px Pretendard", color: C.faint }}>불러오는 중...</div>;

  const hasDraft = draft.draftId !== null;
  const locked = draft.status === "REVIEW";
  const canEdit = hasDraft && CAN.paramDraft(role) && !locked;
  const dirty = JSON.stringify(v) !== JSON.stringify(draft.values);
  const set = <K extends keyof DraftValues>(k: K, value: DraftValues[K]) => setV({ ...v, [k]: value });

  return (
    <div style={{ maxWidth: 1100 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderRadius: 12, background: "rgba(223,164,0,0.1)", border: "1px solid rgba(223,164,0,0.28)", marginBottom: 16 }}>
        <span style={{ font: "600 12.5px Pretendard" }}>파라미터는 즉시 반영되지 않습니다 — 드래프트 → 시뮬레이션·백테스트 → 2인 승인 → 이후 판정부터 적용(비소급).</span>
        <span style={{ marginLeft: "auto", font: "500 11.5px ui-monospace, monospace", color: C.faint }}>
          {hasDraft ? `드래프트 #${draft.draftId!.slice(0, 8)} · ${locked ? "승인 대기중" : "작성중"}` : "진행 중인 드래프트 없음 — 운영값"}
        </span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
        <Card>
          <b style={{ fontSize: 13 }}>판정 파라미터</b>
          <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
            T = 제보자 비율 × 지속성 × 다양성. 하한이 1이면 그 축은 꺼져 있습니다. 판정 입력은 제보뿐입니다(R5).
          </div>
          <Group title="목표치">
            <NumRow label="하한" hint="서로 다른 제보자" cur={draft.current.targetFloor} val={v.targetFloor} step={1} disabled={!canEdit} onChange={(x) => set("targetFloor", x)} />
            <NumRow label="비율" hint="활성 제보자 대비" cur={draft.current.targetRatio} val={v.targetRatio} step={0.01} disabled={!canEdit} onChange={(x) => set("targetRatio", x)} />
            <NumRow label="활성 기간(일)" cur={draft.current.activeWindowDays} val={v.activeWindowDays} step={1} disabled={!canEdit} onChange={(x) => set("activeWindowDays", x)} />
          </Group>
          <Group title="판정">
            <NumRow label="HIT 임계값" cur={draft.current.hitThreshold} val={v.hitThreshold} step={0.01} disabled={!canEdit} onChange={(x) => set("hitThreshold", x)} />
          </Group>
          <Group title="지속성 — 며칠에 걸쳐 제보됐나(KST)">
            <NumRow label="하한" cur={draft.current.persistenceFloor} val={v.persistenceFloor} step={0.05} disabled={!canEdit} onChange={(x) => set("persistenceFloor", x)} />
            <NumRow label="기준일수" cur={draft.current.persistenceFullDays} val={v.persistenceFullDays} step={1} disabled={!canEdit} onChange={(x) => set("persistenceFullDays", x)} />
          </Group>
          <Group title="다양성 — 몇 군데에서 목격됐나(링크 판별)">
            <NumRow label="하한" cur={draft.current.diversityFloor} val={v.diversityFloor} step={0.05} disabled={!canEdit} onChange={(x) => set("diversityFloor", x)} />
            <NumRow label="기준 플랫폼 수" cur={draft.current.diversityFullPlatforms} val={v.diversityFullPlatforms} step={1} disabled={!canEdit} onChange={(x) => set("diversityFullPlatforms", x)} />
          </Group>
          <Group title="독립성 — 같은 기기·IP 계정 묶기">
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "8px 0" }}>
              <span style={{ font: "600 12.5px Pretendard" }}>모드</span>
              <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{MODE_LABEL[draft.current.independenceMode]} →</span>
                <select value={v.independenceMode} disabled={!canEdit} onChange={(e) => set("independenceMode", e.target.value as IndependenceMode)}
                  style={{ padding: "5px 8px", borderRadius: 7, border: `1px solid ${C.line}`, font: "600 12px Pretendard" }}>
                  {(Object.keys(MODE_LABEL) as IndependenceMode[]).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
                </select>
              </span>
            </div>
            {v.independenceMode === "DEVICE_OR_IP" && (
              <div style={{ font: "500 11px Pretendard", color: C.fading, lineHeight: 1.5 }}>
                통신사·카페처럼 IP를 나눠 쓰는 정상 유저가 한 명으로 묶일 수 있습니다. 실사례 백테스트로 확인한 뒤에만 쓰세요.
              </div>
            )}
          </Group>
          {hasDraft && (
            <div style={{ marginTop: 14 }}>
              <Btn disabled={!canEdit || !dirty || busy} onClick={() => act(async () => {
                put(await updateParamDraft(v));
                flash("드래프트에 적용됨 — 시뮬레이션·백테스트를 다시 돌려야 합니다");
              })}>적용</Btn>
            </div>
          )}
        </Card>

        <Card>
          <b style={{ fontSize: 13 }}>시뮬레이션 — 과거 180일 재판정</b>
          {draft.simResult ? (
            <div style={{ marginTop: 16 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                <span style={{ font: "700 32px Pretendard", letterSpacing: "-0.03em" }}>{draft.simResult.changed}</span>
                <span style={{ font: "500 12.5px Pretendard", color: C.sub }}>
                  / {draft.simResult.total}건 판정 변동 ({draft.simResult.total > 0 ? Math.round((draft.simResult.changed / draft.simResult.total) * 100) : 0}%)
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 18 }}>
                <SimRow label="MISS → HIT" count={draft.simResult.missToHit} tone="up" />
                <SimRow label="HIT → MISS" count={draft.simResult.hitToMiss} tone="down" />
                <SimRow label="HIT 확산 레벨 변경" count={draft.simResult.reachChanged} tone="neutral" />
              </div>
            </div>
          ) : (
            <div style={{ padding: "26px 0", textAlign: "center", font: "500 13px Pretendard", color: C.faint, lineHeight: 1.6 }}>
              시뮬레이션을 돌리기 전에는 승인 요청을 보낼 수 없습니다.
            </div>
          )}
        </Card>
      </div>

      <BacktestCard draft={draft} canRun={CAN.paramDraft(role) && hasDraft && !locked && !dirty} busy={busy} act={act} put={put} flash={flash} />

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center" }}>
        {hasDraft && (
          <>
            <Btn disabled={!CAN.paramDraft(role) || locked || dirty || busy} title={dirty ? "먼저 적용하세요" : undefined}
              onClick={() => act(async () => { put(await simulateParamDraft()); flash("시뮬레이션 완료 (과거 180일 재판정)"); })}>시뮬레이션 실행</Btn>
            <input placeholder="승인 요청 사유" value={reason} onChange={(e) => setReason(e.target.value)} disabled={!CAN.paramDraft(role) || locked}
              style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", width: 220 }} />
            <Btn tone="primary" disabled={!draft.simResult || !draft.backtestResult || locked || dirty || !CAN.paramDraft(role) || busy}
              title={!draft.simResult ? "시뮬레이션 먼저" : !draft.backtestResult ? "백테스트 먼저" : undefined}
              onClick={() => act(async () => { put(await requestParamApproval(reason)); flash("승인 요청 생성됨 — 다른 ADMIN 1명의 승인 필요"); })}>
              승인 요청 (비소급)
            </Btn>
          </>
        )}
        <span style={{ marginLeft: "auto", font: "500 11.5px Pretendard", color: C.faint }}>
          적용 승인은 {CAN.paramApply(role) ? "가능(다른 ADMIN 1명의 승인)" : "다른 ADMIN 1명의 승인 필요"}
        </span>
      </div>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const MODE_LABEL: Record<IndependenceMode, string> = { OFF: "끔", DEVICE: "기기", DEVICE_OR_IP: "기기 또는 IP" };
const fmt = (x: number | null) => (x === null ? "—" : x.toFixed(2));

function BacktestCard({ draft, canRun, busy, act, put, flash }: {
  draft: ParameterDraftView; canRun: boolean; busy: boolean;
  act: (fn: () => Promise<void>) => Promise<void>; put: (d: ParameterDraftView) => void; flash: (m: string) => void;
}) {
  const queryClient = useQueryClient();
  const { data: datasets } = useBacktestDatasets();
  const [selected, setSelected] = useState<string>("");
  const [onlyChanged, setOnlyChanged] = useState(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const r = draft.backtestResult;

  useEffect(() => {
    if (!selected && datasets && datasets.length > 0) setSelected(r?.datasetId ?? datasets[0].id);
  }, [datasets, r?.datasetId]);

  const onFile = (file: File) => act(async () => {
    const saved = await uploadBacktestDataset(await file.text());
    await queryClient.invalidateQueries({ queryKey: ["admin", "backtest-datasets"] });
    setSelected(saved.id);
    flash(`데이터셋 '${saved.name}' 준비됨 (${saved.caseCount}건)`);
  });

  const downloadExample = () => act(async () => {
    const data = await fetchBacktestExample();
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    const a = document.createElement("a");
    a.href = url; a.download = "synthetic-v1.json"; a.click();
    URL.revokeObjectURL(url);
  });

  const rows: BacktestCaseRow[] = r ? r.report.rows.filter((row) => !onlyChanged || row.changed) : [];

  return (
    <Card style={{ marginTop: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
        <b style={{ fontSize: 13 }}>백테스트 — 사례 파일로 현재 vs 초안</b>
        <span style={{ font: "500 11px Pretendard", color: C.faint }}>정답 라벨은 평가에만 씁니다 — 판정 입력은 제보뿐(R5)</span>
        <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center" }}>
          <select value={selected} onChange={(e) => setSelected(e.target.value)}
            style={{ padding: "6px 8px", borderRadius: 7, border: `1px solid ${C.line}`, font: "500 12px Pretendard", maxWidth: 260 }}>
            {(datasets ?? []).length === 0 && <option value="">데이터셋 없음</option>}
            {(datasets ?? []).map((d) => <option key={d.id} value={d.id}>{d.name} · {d.caseCount}건 · {d.createdAt}</option>)}
          </select>
          <input ref={fileRef} type="file" accept="application/json,.json" style={{ display: "none" }}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
          <Btn disabled={!canRun || busy} onClick={() => fileRef.current?.click()}>파일 올리기</Btn>
          <Btn disabled={busy} onClick={downloadExample}>예시 받기</Btn>
          <Btn tone="primary" disabled={!canRun || busy || !selected}
            title={!canRun ? "적용한 드래프트에서만 실행" : undefined}
            onClick={() => act(async () => { put(await runBacktest(selected)); flash("백테스트 완료"); })}>실행</Btn>
        </span>
      </div>

      {r ? (
        <>
          <div style={{ font: "500 11.5px Pretendard", color: C.sub, marginTop: 12 }}>
            '{r.datasetName}' {r.caseCount}건 · 해시 {r.sha256.slice(0, 12)} · 판정이 바뀐 사례 {r.report.changedCount}건
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginTop: 10 }}>
            <SideBox title="현재 운영값" side={r.report.current} />
            <SideBox title="초안" side={r.report.draft} highlight />
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 14 }}>
            <b style={{ fontSize: 12.5 }}>사례별</b>
            <label style={{ font: "500 11.5px Pretendard", color: C.sub }}>
              <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} /> 바뀐 사례만
            </label>
          </div>
          <div style={{ marginTop: 6 }}>
            {rows.map((row) => (
              <details key={row.caseId} style={{ padding: "9px 0", borderBottom: `1px solid ${C.line}` }}>
                <summary style={{ cursor: "pointer", display: "flex", gap: 10, alignItems: "center", font: "500 12px Pretendard" }}>
                  <span style={{ font: "600 11px ui-monospace, monospace", color: C.faint, width: 56 }}>{row.caseId}</span>
                  <span style={{ flex: 1 }}>{row.title}</span>
                  <span style={{ font: "600 11px ui-monospace, monospace" }}>정답 {row.label}{row.labelReach ? ` ${row.labelReach}` : ""}</span>
                  <Verdict o={row.current} label={row.label} />
                  <span style={{ color: C.faint }}>→</span>
                  <Verdict o={row.draft} label={row.label} />
                </summary>
                <div style={{ font: "500 11px ui-monospace, monospace", color: C.sub, marginTop: 6, lineHeight: 1.7 }}>
                  <div>현재 · {row.current.explain}</div>
                  <div>초안 · {row.draft.explain}</div>
                </div>
              </details>
            ))}
            {rows.length === 0 && <div style={{ padding: "12px 0", font: "500 12px Pretendard", color: C.faint }}>표시할 사례가 없습니다.</div>}
          </div>
        </>
      ) : (
        <div style={{ padding: "22px 0", textAlign: "center", font: "500 13px Pretendard", color: C.faint, lineHeight: 1.6 }}>
          백테스트를 돌리기 전에는 승인 요청을 보낼 수 없습니다. 실사례가 없으면 "예시 받기"의 합성 시나리오로 형식을 확인하세요.
        </div>
      )}
    </Card>
  );
}

function SideBox({ title, side, highlight }: { title: string; side: BacktestSide; highlight?: boolean }) {
  const c = side.confusion;
  return (
    <div style={{ padding: "12px 14px", borderRadius: 10, background: highlight ? "rgba(223,164,0,0.08)" : "rgba(20,19,15,0.03)" }}>
      <div style={{ font: "600 12px Pretendard" }}>{title}</div>
      <div style={{ display: "flex", gap: 18, marginTop: 8, font: "700 18px ui-monospace, monospace" }}>
        <span>정밀도 {fmt(side.precision)}</span>
        <span>재현율 {fmt(side.recall)}</span>
      </div>
      <div style={{ font: "500 11px ui-monospace, monospace", color: C.sub, marginTop: 6 }}>
        TP {c.tp} · FP {c.fp} · FN {c.fn} · TN {c.tn} · 확산 등급 일치 {fmt(side.reachAgreement)} ({side.reachCompared}건 비교)
      </div>
    </div>
  );
}

function Verdict({ o, label }: { o: { result: string; reach: string | null }; label: string }) {
  const ok = o.result === label;
  return (
    <span style={{ font: "600 11px ui-monospace, monospace", padding: "2px 6px", borderRadius: 5,
      background: ok ? "rgba(27,158,82,0.1)" : "rgba(216,72,60,0.1)", color: ok ? C.rising : C.fading }}>
      {o.result}{o.reach ? ` ${o.reach}` : ""}
    </span>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginTop: 14, paddingTop: 12, borderTop: `1px solid ${C.line}` }}>
      <div style={{ font: "600 11.5px Pretendard", color: C.sub, marginBottom: 2 }}>{title}</div>
      {children}
    </div>
  );
}

function NumRow({ label, hint, cur, val, step, disabled, onChange }: {
  label: string; hint?: string; cur: number; val: number; step: number; disabled: boolean; onChange: (x: number) => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 0" }}>
      <span style={{ font: "600 12.5px Pretendard" }}>
        {label}{hint && <span style={{ font: "500 11px Pretendard", color: C.faint, marginLeft: 6 }}>{hint}</span>}
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{cur} →</span>
        <input type="number" value={val} step={step} disabled={disabled}
          onChange={(e) => onChange(e.target.value === "" ? 0 : Number(e.target.value))}
          style={{ width: 80, padding: "5px 8px", borderRadius: 7, border: `1px solid ${C.line}`,
            font: "600 12.5px ui-monospace, monospace", color: val !== cur ? C.peak : C.ink }} />
      </span>
    </div>
  );
}

function SimRow({ label, count, tone }: { label: string; count: number; tone: "up" | "down" | "neutral" }) {
  const col = tone === "up" ? C.rising : tone === "down" ? C.fading : C.ink;
  const bg = tone === "up" ? "rgba(27,158,82,0.09)" : tone === "down" ? "rgba(216,72,60,0.08)" : "rgba(20,19,15,0.04)";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "11px 13px", borderRadius: 10, background: bg }}>
      <span style={{ font: "600 12.5px Pretendard", color: col }}>{label}</span>
      <span style={{ marginLeft: "auto", font: "700 13px ui-monospace, monospace", color: col }}>{count}건</span>
    </div>
  );
}
```

`Card`가 `style` prop을 받는다(`components/ui.tsx`). 범위 검증은 서버가 한다 — 위반이면 토스트에 서버 메시지("persistenceFloor: 0 ~ 1 사이여야 합니다 …")가 그대로 뜬다.

- [ ] **Step 3: 판정·항목 상세·시딩 화면**

`VerdictScreen.tsx` — 판정 행 첫 칸의 `<span style={{ font: "600 12.5px Pretendard" }}> … </span>`를 `<div>`로 바꾸고 그 안 끝에:

```tsx
                      {it.tExplain && (
                        <div style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint, marginTop: 4 }}>{it.tExplain}</div>
                      )}
```

`TrendDetailScreen.tsx` — "현재 T = …" `<span>` 바로 뒤에:

```tsx
            {detail.previewExplain && (
              <div style={{ font: "500 11px ui-monospace, monospace", color: C.sub, marginTop: 6 }}>{detail.previewExplain}</div>
            )}
```

`SeedScreen.tsx`:
- `EMPTY`에서 `platform: ""` 삭제, `canSubmit`에서 `form.platform.trim() !== "" &&` 삭제
- `<Field label="플랫폼"> … </Field>` 블록 삭제
- `<Field label="근거 URL">`의 `<input … />` 바로 뒤에(같은 Field 안):

```tsx
            {form.evidenceUrl.trim() !== "" && (
              <div style={{ font: "500 11px Pretendard", color: C.faint, marginTop: 4 }}>
                {platformLabel(form.evidenceUrl)} 제보로 기록됩니다(링크로 판별)
              </div>
            )}
```
(import `{ platformLabel } from "../platform"`)

- [ ] **Step 4: 빌드**

Run: `cd admin && npm run build`
Expected: `tsc -b && vite build` 성공. 타입 오류가 나면 `ParameterDraftView`의 옛 필드(`submitterTarget`·`currentSubmitterTarget`·`hitThreshold`·`currentHitThreshold`)를 참조하는 곳을 `grep -rn "submitterTarget\|currentHitThreshold" admin/src`로 찾아 `values`/`current`로 고친다.

- [ ] **Step 5: 커밋**

```bash
git add admin/src/platform.ts admin/src/api/client.ts admin/src/api/types.ts admin/src/api/hooks.ts admin/src/fixtures.ts admin/src/screens/ParamStudioScreen.tsx admin/src/screens/VerdictScreen.tsx admin/src/screens/TrendDetailScreen.tsx admin/src/screens/SeedScreen.tsx
git commit -m "feat(admin): 스튜디오 9개 값·백테스트 탭(업로드·예시·현재 vs 초안), 판정 T 분해 표시, 시딩 플랫폼 입력 삭제(SP4 §9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 11: 앱 — 플랫폼 칩 삭제·기기 ID 헤더

**Files:**
- Create: `app/src/platform.ts`, `app/src/deviceId.ts`
- Modify: `app/src/api/client.ts`, `app/src/api/types.ts`, `app/src/screens/SubmitScreen.tsx`
- Modify: `app/package.json`, `app/package-lock.json`(의존성 추가)

**Interfaces:**
- Consumes: Task 4의 `X-Device-Id` 헤더, `platform` 선택 필드
- Produces: 제보 요청이 기기 ID 헤더를 싣고 플랫폼 칩 없이 간다

- [ ] **Step 1: 의존성**

Run: `cd app && npx expo install expo-application`
Expected: `package.json`에 `"expo-application": "~7.x"`(Expo 54 호환 버전) 추가. 네트워크가 막혀 설치가 안 되면 멈추고 보고한다.

- [ ] **Step 2: 구현**

`app/src/platform.ts` — 관리자 콘솔의 `admin/src/platform.ts`와 **같은 내용**(Task 10 Step 1의 코드 그대로, 주석의 "앱(app/src/platform.ts)"만 "콘솔(admin/src/platform.ts)"로).

`app/src/deviceId.ts`:

```ts
import * as Application from "expo-application";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { Platform } from "react-native";

/**
 * 제보의 기기 ID(SP4 §4) — 서버가 HMAC 해시로만 저장한다. 안드로이드 ID, iOS 제조사별 ID,
 * 웹은 처음 실행할 때 만든 무작위 ID. 얻지 못하면 null(헤더 없이 보낸다 — 제보는 된다).
 */
const WEB_KEY = "trd.installId";
let cached: string | null | undefined;

export async function deviceId(): Promise<string | null> {
  if (cached !== undefined) return cached;
  try {
    if (Platform.OS === "android") {
      cached = Application.getAndroidId();
    } else if (Platform.OS === "ios") {
      cached = await Application.getIosIdForVendorAsync();
    } else {
      let id = await AsyncStorage.getItem(WEB_KEY);
      if (!id) {
        id = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
        await AsyncStorage.setItem(WEB_KEY, id);
      }
      cached = id;
    }
  } catch {
    cached = null;
  }
  return cached ?? null;
}
```

`client.ts`의 `request` — 헤더에 기기 ID(128자로 자른다):

```ts
async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = tokenProvider();
  const device = await deviceId();
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      // 본문이 없는 요청(DELETE 등)에는 Content-Type을 붙이지 않는다 — 규격에 맞지 않고 일부 스택이 거부한다.
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      // 서버는 해시만 저장한다(SP4 §4)
      ...(device ? { "X-Device-Id": device.slice(0, 128) } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
```
(import `{ deviceId } from "../deviceId"`)

`types.ts`의 `SubmissionCreate`에서 `platform: string;` 삭제.

`SubmitScreen.tsx`:
- `const PLATS = [...]` 삭제, `const [plat, setPlat] = useState<string | null>(null);` 삭제
- `ready`에서 `plat &&` 삭제
- `submit.mutate({...})`에서 `platform: plat!,` 삭제, `onSuccess`에서 `setPlat(null);` 삭제
- `<Field label="최초 목격 플랫폼"> … </Field>` 삭제
- `<Field label="근거 URL">…</Field>` 바로 뒤에:

```tsx
      {url.trim().length > 8 && (
        <Muted style={{ marginTop: -10, fontSize: 12.5 }}>
          {platformLabel(url) === "기타" ? "기타 사이트 제보로 기록돼요" : `${platformLabel(url)} 제보로 기록돼요`}
        </Muted>
      )}
```
- 버튼 문구 `"항목명 · 카테고리 · 플랫폼 · URL 필요"` → `"항목명 · 카테고리 · URL 필요"`
(import `{ platformLabel } from "../platform"`)

- [ ] **Step 3: 타입 검사**

Run: `cd app && npm run typecheck`
Expected: 오류 없음

- [ ] **Step 4: 커밋**

```bash
git add app/src/platform.ts app/src/deviceId.ts app/src/api/client.ts app/src/api/types.ts app/src/screens/SubmitScreen.tsx app/package.json app/package-lock.json
git commit -m "feat(app): 플랫폼 칩 삭제(링크 판별 미리보기), 제보에 기기 ID 헤더(SP4 §4·§9)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
(`app/package-lock.json`이 없으면 빼고, 대신 생긴 잠금 파일이 있으면 그것을 add)

---

### Task 12: 문서·설정·전체 검증

**Files:**
- Modify: `CLAUDE.md`, `01-system-design.md`, `02-admin-console.md`, `04-development-plan.md`, `05-screen-endpoint-map.md`, `README.md`
- Modify: `infra/docker-compose.yml`, `infra/.env.example`

- [ ] **Step 1: CLAUDE.md**

- R5의 괄호 문구 `(현행 코드는 제보자 수 축만 구현. 나머지 축은 SP4에서 Phase 0 백테스트와 함께 도입.)` → `(SP4에서 4축 구현 — 유효 T = 제보자 비율(상대 목표치·독립성 압축) × 지속성 × 다양성. 기본값은 중립이라 판정은 제보자 수 축과 같고, 값은 백테스트(ADM-600)를 거쳐 2인 승인으로 켠다.)`
- 핵심 도메인 표의 **T** 행: `유효 T = 제보자 비율 × 지속성 × 다양성 (0~1). 제보자 비율 = 독립 제보자 수 / 목표치(max(하한, 활성 제보자 × 비율)). 시딩·VOID 제외`
- 아키텍처 흐름 `[D+14 판정 엔진 (제보 집계 — 현재는 제보자 수 축)]` → `[D+14 판정 엔진 (제보 4축 — 기본 중립)]`
- 관리자 콘솔 `**파라미터 스튜디오(ADM-600)**` 줄: `시뮬레이션·백테스트 없이 승인 요청 불가. 편집 값 9개(목표치 하한·비율·활성 기간, HIT 임계, 지속성·다양성 하한·기준, 독립성 모드). 사례 파일은 불변 보관. 승인되면 이후 판정부터(비소급).`
- 열린 결정 표: O1 `**도구 완료(SP4)** — 상대 목표치·백테스트 탭. 값은 실사례 30건 백테스트 후`, O8 `**도구 완료(SP4)**, 기본 중립. 가중치는 실사례 백테스트 후`
- 법무 체크리스트 "디바이스 지문·IP 수집…" 항목 끝에 ` — **SP4부터 해시로 수집. 클로즈드 베타 전 필수, 해시 보존 기간 명시**`
- "구현 시 자주 틀리는 지점"에 한 절 추가:

```markdown
### ⚠ 판정 근거에는 해시를 넣지 않는다

`evidence_json`은 지울 수 없다(R2). 기기·IP는 `submissions`에 HMAC 해시로만 두고, 판정 근거에는 **그 판정 안에서만 의미 있는 그룹 번호**를 동결한다(SP4 S7). SP4 이전 근거는 새 축 값이 없다 — 읽을 때 **중립(1·하한·압축 없음)**으로 읽어야 하고, 0으로 채우면 전부 감점이 된다(`ParamsSnapshot.toParameterSet`, `LegacyEvidenceTest`).
```

- [ ] **Step 2: 설계서**

- `01-system-design.md` §4: 판정 공식을 스펙 §2.1 그대로 옮기고(4축·기본 중립), "첫 48시간 집중도"를 삭제, 플랫폼은 "근거 링크 도메인으로 서버가 판별(목록 11개)"로, 독립성은 "기기·IP 해시, 모드 OFF/DEVICE/DEVICE_OR_IP, 기본 OFF"로.
- `02-admin-console.md` ADM-600: 편집 값 9개 표(스펙 §5.1), 백테스트 탭(업로드·예시·현재 vs 초안·사례별), 승인 조건(시뮬레이션 + 백테스트), 사례 파일 형식은 스펙 §5.2 참조. ADM-500 시딩 폼에서 플랫폼 입력 삭제.
- `04-development-plan.md`: 마이그레이션 목록에 V31(+V31_1 Java, V31_2), 환경변수 `SIGNAL_HASH_SECRET`·`SERVER_FORWARD_HEADERS_STRATEGY`.
- `05-screen-endpoint-map.md`: ADM-600 행에 `/admin/params/draft/backtest`, `/admin/params/backtest-datasets`(GET·POST), `/backtest-datasets/example`.

- [ ] **Step 3: 인프라·README**

`infra/docker-compose.yml` — backend 서비스 `environment`에:

```yaml
      # 제보 기기·IP 해시 비밀값(SP4). 없으면 compose가 여기서 멈춘다 — infra/.env에 넣는다.
      SIGNAL_HASH_SECRET: "${SIGNAL_HASH_SECRET:?infra/.env에 SIGNAL_HASH_SECRET을 넣으세요 (openssl rand -hex 32)}"
      SERVER_FORWARD_HEADERS_STRATEGY: "${SERVER_FORWARD_HEADERS_STRATEGY:-none}"
```

`infra/.env.example` — 배치 스케줄 절 앞에:

```
# ── 제보 기기·IP 해시(SP4) ───────────────────────────────────────────────
# 필수. 기기 ID·IP 원문은 저장하지 않고 이 값으로 만든 HMAC만 저장한다. 한 번 정하면 바꾸지 않는다 —
# 바꾸면 이전 제보와 같은 기기·IP로 묶이지 않는다. 만들기: openssl rand -hex 32
SIGNAL_HASH_SECRET=
# 백엔드를 리버스 프록시 뒤에 둘 때만 native로 바꾼다(프록시가 X-Forwarded-For를 덮어써야 한다).
# SERVER_FORWARD_HEADERS_STRATEGY=native
```

`README.md` — 로컬 실행 절에 "`infra/.env`에 `SIGNAL_HASH_SECRET`이 없으면 docker compose가 시작하지 않는다(SP4). `openssl rand -hex 32`로 만든 값을 넣는다. 로컬에서 `bootRun`하면 `backend/app/.env`나 환경변수로 준다." 한 단락.

- [ ] **Step 4: 전체 검증**

Run: `<태스크>` = `build`
Expected: 모든 테스트 통과(SP3 병합 시점 204개 + SP4 신규). 실패가 있으면 멈추고 보고한다.

Run: `cd admin && npm run build`, `cd app && npm run typecheck`
Expected: 성공

Run(openapi): Task 9 Step 4의 명령
Expected: valid

- [ ] **Step 5: 커밋**

```bash
git add CLAUDE.md 01-system-design.md 02-admin-console.md 04-development-plan.md 05-screen-endpoint-map.md README.md infra/docker-compose.yml infra/.env.example
git commit -m "docs: SP4 반영 — 판정 4축(기본 중립)·링크 판별 플랫폼·기기/IP 해시·백테스트 탭, SIGNAL_HASH_SECRET

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
