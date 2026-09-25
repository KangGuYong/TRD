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
                .isEqualTo("T 0.29 = 제보자 5/8 (0.63) × 지속성 0.63 (1일 · 기준 4일) × 다양성 0.75 (2곳 · 기준 3곳)");
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
