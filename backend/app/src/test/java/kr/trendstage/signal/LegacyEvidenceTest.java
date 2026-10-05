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
