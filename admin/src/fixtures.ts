/** dev 시연용 데이터. 관리자 콘솔.dc.html 예시를 반영. 실 API로 대체됨. */
import type { AdminUserDetail, AuditEntry, MergeCandidate, ParameterPayload, QueueSummary, SimulationResult } from "./api/types";

export const fxQueueSummary: QueueSummary = {
  slaBreaches: 3,
  queues: [
    { id: "ADM-100", name: "병합 검수", count: 24, oldest: "4h", slaExceeded: false },
    { id: "ADM-300", name: "어뷰징", count: 6, oldest: "11h", slaExceeded: false },
    { id: "ADM-400", name: "이의 제기", count: 3, oldest: "26h", slaExceeded: true },
    { id: "ADM-410", name: "신고", count: 2, oldest: "3h", slaExceeded: false },
  ],
  alerts: [
    { title: "이의 제기 큐 SLA 초과 3건", detail: "5영업일 기준 초과, 상위 역할 확인 필요" },
  ],
  seedRatio: 0.41,
  judgedToday: 17,
  imminent24h: 9,
};

export const fxMergeQueue: MergeCandidate[] = [
  {
    id: "mq-1",
    similarity: 0.82,
    newName: "새싹챌린지",
    oldName: "새싹 챌린지",
    oldClusterId: "#1204",
    ago: "2h 전",
    newRows: [
      { k: "카테고리", v: "챌린지" },
      { k: "플랫폼", v: "인스타" },
      { k: "제보자", v: "user_8821 · TI 0.51 / L2" },
      { k: "URL", v: "2건" },
    ],
    oldRows: [
      { k: "제보", v: "4건 (order 1~4)" },
      { k: "최초", v: "3일 전" },
      { k: "상태", v: "PENDING · 현재 T 추정 0.31" },
    ],
    orderPreview: ["order1 user_4410", "order2 user_8821", "order3 user_0092", "order4 신규", "order5 신규"],
  },
];

export const fxParams: ParameterPayload = {
  submitterTarget: 20,
  hitThreshold: 0.2,
  halflifeDays: 90,
};

export const fxSimulation: SimulationResult = {
  changed: 28,
  total: 342,
  rows: [
    { label: "MISS → HIT", count: 11, tone: "up" },
    { label: "HIT → MISS", count: 17, tone: "down" },
    { label: "등급 강등 / 승급", count: 0, tone: "neutral" },
  ],
  affectedUsers: 14,
  avgDelta: -23,
  demotions: 3,
  promotions: 1,
};

export const fxUser: AdminUserDetail = {
  userId: "user_4410",
  grade: "L3 분석가",
  activeScore: 512,
  trustIndex: 0.63,
  judgedCount: 47,
  hit: 29,
  miss: 18,
  flags: 0,
  joinedAt: "2026-02-11",
  ledger: [
    { date: "08-05", kind: "HIT", delta: "+75.0", reason: "#1204 order1 c50 m1.0" },
    { date: "08-01", kind: "MISS", delta: "−15.0", reason: "#1188 c30" },
    { date: "07-28", kind: "ADJ", delta: "+12.0", reason: "판정 재계산 보정 (승인:박관리)" },
    { date: "07-21", kind: "HIT", delta: "+60.0", reason: "#1150 order1 c30 m2.0" },
  ],
};

export const fxAudit: AuditEntry[] = [
  { id: 5012, actor: "김운영", role: "OPERATOR", action: "MERGE", targetType: "TREND", targetId: "#1204", createdAt: "2026-08-07 16:41" },
  { id: 5011, actor: "박관리", role: "ADMIN", action: "LEDGER_ADJ", targetType: "USER", targetId: "user_4410", createdAt: "2026-08-07 15:20" },
  { id: 5010, actor: "이감사", role: "AUDITOR", action: "PII_VIEW", targetType: "USER", targetId: "user_7731", createdAt: "2026-08-07 14:03" },
  { id: 5009, actor: "김운영", role: "OPERATOR", action: "VOID", targetType: "TREND", targetId: "#1188", createdAt: "2026-08-07 11:47" },
  { id: 5008, actor: "박관리", role: "ADMIN", action: "PARAM_APPLY", targetType: "DRAFT", targetId: "#16", createdAt: "2026-08-06 09:00" },
];
