/** 디자인 토큰. 색은 '단계'에만 쓰고 나머지는 전부 무채색(앱 설계 원칙 04). */
export const C = {
  seed: "#9A968C",
  rising: "#1B9E52",
  peak: "#DFA400",
  fading: "#D8483C",
  ink: "#14130F",
  bg: "#F4F2ED",
  card: "#FFFFFF",
  line: "rgba(20,19,15,0.08)",
  sub: "rgba(20,19,15,0.55)",
  faint: "rgba(20,19,15,0.4)",
} as const;

export type Stage = "SEED" | "RISING" | "PEAK" | "FADING";

export const STAGE_COLOR: Record<Stage, string> = {
  SEED: C.seed,
  RISING: C.rising,
  PEAK: C.peak,
  FADING: C.fading,
};

export const STAGE_TINT: Record<Stage, string> = {
  SEED: "rgba(154,150,140,0.13)",
  RISING: "rgba(27,158,82,0.11)",
  PEAK: "rgba(223,164,0,0.13)",
  FADING: "rgba(216,72,60,0.11)",
};

export const STAGE_LABEL: Record<Stage, string> = {
  SEED: "씨앗",
  RISING: "급상승",
  PEAK: "정점",
  FADING: "식는 중",
};
