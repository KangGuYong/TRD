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
