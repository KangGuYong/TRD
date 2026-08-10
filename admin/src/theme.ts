/** 콘솔 디자인 토큰 (관리자 콘솔.dc.html). 색은 단계/상태에만. */
export const C = {
  ink: "#14130F",
  bg: "#F4F2ED",
  card: "#FFFFFF",
  side: "#14130F",
  line: "rgba(20,19,15,0.08)",
  sub: "rgba(20,19,15,0.55)",
  faint: "rgba(20,19,15,0.4)",
  seed: "#9A968C",
  rising: "#1B9E52",
  peak: "#DFA400",
  fading: "#D8483C",
} as const;

export type Role = "REVIEWER" | "OPERATOR" | "ADMIN" | "AUDITOR";
export const ROLE_LABEL: Record<Role, string> = {
  REVIEWER: "검수자",
  OPERATOR: "운영자",
  ADMIN: "관리자",
  AUDITOR: "감사자",
};
