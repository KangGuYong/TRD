package kr.trendstage.scheduler;

import kr.trendstage.domain.verdict.SignalNormalizer;
import kr.trendstage.domain.verdict.SignalScores;
import kr.trendstage.persistence.entity.MetricSnapshot;
import kr.trendstage.persistence.entity.TrendItem;
import kr.trendstage.persistence.repo.MetricSnapshotRepository;
import kr.trendstage.persistence.type.MetricSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 원시 metric_snapshots → 정규화 SignalScores 변환. 01 §4.2.
 * baseline = 최초 제보 직전 14일, 관측 = 최초 제보 ~ +14일. 소스별 일별 평균의 비율을 s_i로.
 *
 * <p><b>주의</b>: 정확한 일자 버킷팅·소스별 대표 지표 선택은 Phase 0 백테스트로 보정한다(O1).
 * 여기서는 배선(wiring)과 결측 판정에 집중한다. 누적치가 아니라 일별값을 넣는 전제(R5).
 */
@Component
public class MetricAggregator {

    /** S1..S5 순서 고정. */
    private static final MetricSource[] ORDER = {
            MetricSource.X, MetricSource.DCINSIDE, MetricSource.NAVER_DATALAB,
            MetricSource.INSTAGRAM, MetricSource.DERIVED
    };
    private static final Duration WINDOW = Duration.ofDays(14);

    public record Result(SignalScores signals, int missingCount) {}

    private final MetricSnapshotRepository metrics;

    public MetricAggregator(MetricSnapshotRepository metrics) { this.metrics = metrics; }

    public Result aggregate(TrendItem item) {
        Instant firstSeen = item.getFirstSeenAt();
        Instant baseStart = firstSeen.minus(WINDOW);
        Instant obsEnd = firstSeen.plus(WINDOW);

        List<MetricSnapshot> base = metrics.findByTrendItemIdAndCapturedAtBetween(item.getId(), baseStart, firstSeen);
        List<MetricSnapshot> obs = metrics.findByTrendItemIdAndCapturedAtBetween(item.getId(), firstSeen, obsEnd);

        double[] r = new double[5];
        int missing = 0;
        for (int i = 0; i < ORDER.length; i++) {
            MetricSource src = ORDER[i];
            double obsAvg = avg(obs, src);
            double baseAvg = avg(base, src);
            if (Double.isNaN(obsAvg)) {          // 관측 데이터 없음 = 결측
                missing++;
                r[i] = 0.0;                       // s_i = 0
            } else {
                r[i] = SignalNormalizer.ratio(obsAvg, Double.isNaN(baseAvg) ? 0.0 : baseAvg);
            }
        }
        SignalScores s = SignalNormalizer.fromRatios(r[0], r[1], r[2], r[3], r[4]);
        return new Result(s, missing);
    }

    /** 소스의 값 평균(일별값 전제). 데이터 없으면 NaN. */
    private static double avg(List<MetricSnapshot> list, MetricSource src) {
        double sum = 0; int n = 0;
        for (MetricSnapshot m : list) {
            if (m.getSource() == src) { sum += m.getValue().doubleValue(); n++; }
        }
        return n == 0 ? Double.NaN : sum / n;
    }
}
