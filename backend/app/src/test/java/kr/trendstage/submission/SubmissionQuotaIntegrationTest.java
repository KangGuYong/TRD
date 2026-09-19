package kr.trendstage.submission;

import kr.trendstage.apipublic.service.QuotaService;
import kr.trendstage.apipublic.service.SubmissionService;
import kr.trendstage.apipublic.web.DuplicateSubmissionException;
import kr.trendstage.apipublic.web.ItemClosedException;
import kr.trendstage.apipublic.web.QuotaExhaustedException;
import kr.trendstage.apipublic.web.SubmissionCreateRequest;
import kr.trendstage.apipublic.web.SubmissionMineResponse;
import kr.trendstage.judge.JudgeService;
import kr.trendstage.persistence.type.TrendCategory;
import kr.trendstage.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 스펙 §7.2 6·12·13번 — 제보권(J4)과 관측 마감(J6). */
class SubmissionQuotaIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant MONDAY = ZonedDateTime.of(2026, 9, 21, 10, 0, 0, 0, KST).toInstant();

    @Autowired SubmissionService service;
    @Autowired QuotaService quota;
    @Autowired JudgeService judge;

    @Test
    void l0GetsTwoPerWeekRefundOnVoidAndRefillOnMonday() {   // #12
        clock.set(MONDAY);
        UUID u = fx.user();
        SubmissionMineResponse first = service.create(u, req(unique()));
        service.create(u, req(unique()));
        assertThatThrownBy(() -> service.create(u, req(unique()))).isInstanceOf(QuotaExhaustedException.class);

        fx.voidSubmission(first.id(), MONDAY.plusSeconds(60));   // 이번 주 VOID → 한 장 반환
        service.create(u, req(unique()));
        assertThatThrownBy(() -> service.create(u, req(unique()))).isInstanceOf(QuotaExhaustedException.class);

        clock.set(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, KST).toInstant());   // 다음 월요일 00:00 KST — 리필
        service.create(u, req(unique()));
        assertThat(quota.of(u, clock.instant()).used()).isEqualTo(1);
    }

    @Test
    void duplicatesAndSeedsDoNotConsumeQuota() {
        clock.set(MONDAY);
        UUID u = fx.user();
        String name = unique();
        service.create(u, req(name));
        assertThatThrownBy(() -> service.create(u, req(name))).isInstanceOf(DuplicateSubmissionException.class);
        fx.seedSubmission(u, fx.item(MONDAY), MONDAY);

        assertThat(quota.of(u, MONDAY).used()).isEqualTo(1);
    }

    @Test
    void concurrentSubmissionsCannotExceedLimit() throws Exception {   // #13
        clock.set(MONDAY);
        UUID u = fx.user();
        service.create(u, req(unique()));   // 남은 제보권 1

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String name = unique();
            results.add(pool.submit(() -> {
                go.await();
                try {
                    service.create(u, req(name));
                    return true;
                } catch (QuotaExhaustedException e) {
                    return false;
                }
            }));
        }
        go.countDown();
        int ok = 0;
        for (Future<Boolean> f : results) if (f.get(30, TimeUnit.SECONDS)) ok++;
        pool.shutdown();

        assertThat(ok).isEqualTo(1);
    }

    @Test
    void closedItemsRejectWithoutCharge() {   // #6(제보 쪽)
        clock.set(MONDAY);
        String name = unique();
        SubmissionMineResponse opener = service.create(fx.user(), req(name));   // first_seen = MONDAY, 마감 = +14일

        clock.set(MONDAY.plus(Duration.ofDays(14)).minusSeconds(1));
        service.create(fx.user(), req(name));   // 마감 직전은 받는다

        clock.set(MONDAY.plus(Duration.ofDays(14)));
        UUID late = fx.user();
        assertThatThrownBy(() -> service.create(late, req(name))).isInstanceOf(ItemClosedException.class);
        assertThat(quota.of(late, clock.instant()).used()).isZero();

        UUID item = jdbc.queryForObject("SELECT trend_item_id FROM submissions WHERE id = ?", UUID.class, opener.id());
        judge.closeDue(clock.instant());
        judge.judge(item, clock.instant());
        assertThatThrownBy(() -> service.create(fx.user(), req(name))).isInstanceOf(ItemClosedException.class);
    }

    @Test
    void quotaExhaustedIsProblem422WithType() throws Exception {
        clock.set(MONDAY);
        UUID u = fx.user();
        service.create(u, req(unique()));
        service.create(u, req(unique()));

        mvc.perform(post("/v1/submissions")
                        .with(authentication(new UsernamePasswordAuthenticationToken(u, null, List.of())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + unique() + "\",\"category\":\"MEME\",\"platform\":\"X\","
                                + "\"evidenceUrl\":\"https://example.com\",\"confidence\":30,\"disclosure\":false,\"oneLine\":\"설명\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("quota-exhausted"));
    }

    private static SubmissionCreateRequest req(String name) {
        return new SubmissionCreateRequest(name, TrendCategory.MEME, "X", "https://example.com", 30, false, "설명");
    }

    private static String unique() {
        return "제보" + UUID.randomUUID().toString().substring(0, 8);
    }
}
