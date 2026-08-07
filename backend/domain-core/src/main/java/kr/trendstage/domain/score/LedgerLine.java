package kr.trendstage.domain.score;

import kr.trendstage.domain.verdict.VerdictResult;

import java.util.UUID;

/**
 * 판정으로 발생할 원장 한 줄(계획). kind는 판정 결과 그대로(HIT/MISS/VOID).
 * ADJ(상쇄)는 관리자 경로에서만 생기므로 여기 없다.
 * reason은 산정 근거 문자열(그대로 원장·응답에 노출).
 */
public record LedgerLine(UUID userId, UUID submissionId, VerdictResult kind, double delta, String reason) {}
