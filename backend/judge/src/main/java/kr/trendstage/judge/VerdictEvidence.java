package kr.trendstage.judge;

import kr.trendstage.domain.verdict.TrendSignal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * verdicts.evidence_json 형식. 재판정(원 판정 파라미터 — J2)·파라미터 시뮬레이션(신호)·감사의 입력이다.
 * 판정 행과 함께 한 번 기록되면 바뀌지 않는다(append-only). VOID 판정은 params·signal이 null이다.
 */
public record VerdictEvidence(String result, String reach, BigDecimal t, Instant deadline, ParamsSnapshot params,
                              TrendSignal signal, int distinctSubmitters, int distinctPlatforms,
                              Map<UUID, Integer> orderRanks, UUID supersededVerdictId, String adminReason) {}
