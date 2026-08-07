package kr.trendstage.domain.trend;

/** 앱 표시용 생애주기 단계. 색은 이 단계에만 쓴다(앱 설계 원칙 04). */
public enum DisplayStage {
    SEED("씨앗"), RISING("급상승"), PEAK("정점"), FADING("식는 중");

    private final String label;
    DisplayStage(String label) { this.label = label; }
    public String label() { return label; }
}
