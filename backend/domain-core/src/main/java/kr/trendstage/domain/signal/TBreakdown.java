package kr.trendstage.domain.signal;

import java.util.Locale;

/**
 * 유효 T의 분해(스펙 §2). 판정 근거·ADM-111/200·스튜디오·백테스트가 같은 값을 표시한다.
 * activeSubmitters가 null이면 SP4 이전 판정이거나 백테스트 사례에 값이 없는 것 — 목표치는 하한이다.
 * diversityApplied가 false면 플랫폼 코드가 없는 제보(SP4 이전)가 섞여 다양성을 1로 둔 것이다(S8).
 */
public record TBreakdown(int accounts, int independent, int target, Integer activeSubmitters, double ratio,
                         int activeDays, int persistenceFullDays, double persistence,
                         int platforms, int diversityFullPlatforms, boolean diversityApplied, double diversity,
                         double t) {

    /** 예: "T 0.48 = 제보자 15/20 (0.75) × 지속성 0.80 (2일/5일) × 다양성 0.80 (2곳/3곳)" */
    public String describe() {
        String who = accounts == independent
                ? "제보자 %d/%d (%s)".formatted(independent, target, f(ratio))
                : "제보자 %d/%d (%s · 계정 %d → 독립 %d)".formatted(independent, target, f(ratio), accounts, independent);
        String div = diversityApplied
                ? "다양성 %s (%d곳/%d곳)".formatted(f(diversity), platforms, diversityFullPlatforms)
                : "다양성 %s (플랫폼 판별 없음)".formatted(f(diversity));
        return "T %s = %s × 지속성 %s (%d일/%d일) × %s".formatted(
                f(t), who, f(persistence), activeDays, persistenceFullDays, div);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
