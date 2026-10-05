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
