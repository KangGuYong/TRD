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
