package kr.trendstage.domain.params;

/** ADM-600 시뮬레이션 결과 집계. */
public record SimulationSummary(int changed, int total, int missToHit, int hitToMiss, int reachChanged) {}
