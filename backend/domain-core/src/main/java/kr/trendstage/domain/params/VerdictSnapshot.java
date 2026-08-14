package kr.trendstage.domain.params;

import kr.trendstage.domain.verdict.ReachLevel;
import kr.trendstage.domain.verdict.VerdictResult;

/** 과거 판정 1건의 재평가에 필요한 최소 스냅샷 — evidence_json에서 복원. */
public record VerdictSnapshot(VerdictResult result, ReachLevel reach, int distinctSubmitters, int distinctPlatforms) {}
