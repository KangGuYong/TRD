package kr.trendstage.domain.verdict;

/**
 * 정규화된 지표 점수 s_S1..s_S5 (각 0~1). 01 §4.2.
 * 원시 지표 → baseline 대비 배수 r_i → s_i = clip(log2(r_i)/3, 0, 1) 변환은
 * 수집/정규화 계층에서 끝난 뒤 이 값이 들어온다(R5).
 */
public record SignalScores(double s1, double s2, double s3, double s4, double s5) {}
