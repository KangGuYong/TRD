package kr.trendstage.merge;

import kr.trendstage.apiadmin.seed.AdminSeedService;
import kr.trendstage.apipublic.service.SubmissionOrigin;
import kr.trendstage.apipublic.service.SubmissionService;
import kr.trendstage.apipublic.service.WatchService;
import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.WatchItemResponse;
import kr.trendstage.persistence.type.AdminRole;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 스펙 §8 10·11·12번(K6, §4.2): 병합된 항목 이름은 승자로 합류, 같은 새 이름 동시 첫 제보는 한 항목으로. */
class TombstoneJoinTest extends AbstractIntegrationTest {

    @Autowired SubmissionService submissions;
    @Autowired WatchService watches;
    @Autowired AdminSeedService seeds;

    private Instant now;

    @BeforeEach
    void setUp() {
        now = clock.instant();
    }

    private static SubmissionCreateRequest req(String name) {
        return new SubmissionCreateRequest(name, TrendCategory.MEME, "X", "https://example.com", 30, false, "설명");
    }

    private UUID itemOfUserSubmission(UUID userId) {
        return jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE user_id = ?", UUID.class, userId);
    }

    @Test
    void submissionOnMergedNameJoinsSurvivor() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID u = fx.user();

        submissions.create(u, req(fx.key(l)), SubmissionOrigin.NONE);   // 지금까지는 422 item-closed

        assertThat(itemOfUserSubmission(u)).isEqualTo(s);
    }

    @Test
    void twoHopChainIsFollowed() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        UUID l2 = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        fx.merged(l2, l);
        UUID u = fx.user();

        submissions.create(u, req(fx.key(l2)), SubmissionOrigin.NONE);

        assertThat(itemOfUserSubmission(u)).isEqualTo(s);
    }

    @Test
    void userWhoAlreadySubmittedOnSurvivorIsDuplicate() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID u = fx.user();
        fx.submission(u, s, 30, now.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> submissions.create(u, req(fx.key(l)), SubmissionOrigin.NONE))
                .isInstanceOf(DuplicateSubmissionException.class);
    }

    @Test
    void watchOnMergedChainShowsSurvivorStage() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID sub1 = fx.submission(fx.user(), s, 30, now.minus(Duration.ofHours(2)));
        fx.submission(fx.user(), s, 30, now.minus(Duration.ofHours(1)));
        jdbc.update("UPDATE submissions SET platform = 'INSTAGRAM' WHERE id = ?", sub1);   // 플랫폼 2곳 → RISING
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        UUID l2 = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        fx.merged(l2, l);   // 한 단계만 따라가면 제보 없는 l에서 멈춰 SEED가 된다
        UUID u = fx.user();

        watches.add(u, fx.key(l2));
        List<WatchItemResponse> list = watches.list(u);

        assertThat(list).singleElement().extracting(WatchItemResponse::stage).isEqualTo("RISING");
    }

    @Test
    void seedOnMergedNameJoinsSurvivor() {
        UUID s = fx.item(now.minus(Duration.ofDays(1)));
        UUID l = fx.item(now.minus(Duration.ofDays(1)));
        fx.merged(l, s);
        UUID admin = fx.admin();

        AdminSeedService.SeedSubmissionResult r = seeds.registerSeed(admin, AdminRole.ADMIN,
                new AdminSeedService.SeedSubmissionRequest(fx.key(l), "MEME", "X", "https://example.com", 30, "설명"));

        assertThat(r.trendItemId()).isEqualTo(s.toString());
    }

    @Test
    void concurrentFirstSubmissionsOfSameNewNameConvergeOnOneItem() throws Exception {
        String name = "동시첫제보-" + UUID.randomUUID().toString().substring(0, 8);
        UUID u1 = fx.user(), u2 = fx.user();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (UUID u : List.of(u1, u2)) {
            futures.add(pool.submit(() -> {
                start.await();
                submissions.create(u, req(name), SubmissionOrigin.NONE);
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) f.get(30, TimeUnit.SECONDS);   // 500(UNIQUE 위반)이면 여기서 ExecutionException
        pool.shutdown();

        Integer items = jdbc.queryForObject("SELECT count(*) FROM trend_items WHERE normalized_key = ?",
                Integer.class, name.toLowerCase());
        assertThat(items).isEqualTo(1);
        assertThat(itemOfUserSubmission(u1)).isEqualTo(itemOfUserSubmission(u2));
    }
}
