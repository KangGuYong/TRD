/** dev 시연용 데이터. 관리자 콘솔.dc.html 예시를 반영. 실 API로 대체됨. */
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, ImminentItem, JudgedItem, MergeCandidate, MergePreview, ParameterPayload, QueueSummary, SimulationResult, VerdictListResponse } from "./api/types";

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
    newSubmissions: [
      { handle: "user_8821", rawInput: "새싹챌린지", oneLine: "요즘 카페에서 새싹 올린 음료 챌린지 유행 중", evidenceUrl: "https://instagram.com/p/example1", createdAt: "2h 전" },
      { handle: "user_0092", rawInput: "새싹 올리기 챌린지", oneLine: "인스타에서 새싹 사진 릴레이", evidenceUrl: "https://instagram.com/p/example2", createdAt: "1h 전" },
    ],
    oldSubmissions: [
      { handle: "user_4410", rawInput: "새싹 챌린지", oneLine: "새싹 인테리어 유행", evidenceUrl: "https://instagram.com/p/example3", createdAt: "3일 전" },
      { handle: "user_1122", rawInput: "새싹 챌린지", oneLine: "새싹 키우기 챌린지 확산 중", evidenceUrl: "", createdAt: "2일 전" },
    ],
  },
];

export const fxMergePreview: MergePreview = {
  newCanonicalName: "새싹 챌린지",
  orderRank: [
    { handle: "user_4410", rankBefore: 1, rankAfter: 1 },
    { handle: "user_1122", rankBefore: 2, rankAfter: 2 },
    { handle: "user_8821", rankBefore: 1, rankAfter: 3 },
    { handle: "user_0092", rankBefore: 2, rankAfter: 4 },
  ],
  firstSeenAtBefore: "2026-08-11 09:00",
  firstSeenAtAfter: "2026-08-11 09:00",
  baselineShifted: false,
  dedupVoidedHandles: [],
};

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

export const fxAdminAccounts: AdminAccountSummary[] = [
  { id: "a1", loginId: "admin", displayName: "박관리", role: "ADMIN", lastLoginAt: "2026-08-07 09:12", disabledAt: null, createdAt: "2026-07-01 10:00" },
  { id: "a2", loginId: "op_kim", displayName: "김운영", role: "OPERATOR", lastLoginAt: "2026-08-07 16:41", disabledAt: null, createdAt: "2026-07-05 11:30" },
  { id: "a3", loginId: "auditor_lee", displayName: "이감사", role: "AUDITOR", lastLoginAt: "2026-08-06 14:03", disabledAt: null, createdAt: "2026-07-10 09:00" },
  { id: "a4", loginId: "reviewer_old", displayName: "퇴사자", role: "REVIEWER", lastLoginAt: "2026-06-01 09:00", disabledAt: "2026-06-15 00:00", createdAt: "2026-05-01 09:00" },
];

const fxJudged: JudgedItem[] = [
  { trendItemId: "t1", canonicalName: "탕후루 챌린지", result: "HIT", reachLevel: "L2", scoreT: "0.5500", judgedAt: "2026-08-07 03:00", superseded: false },
  { trendItemId: "t2", canonicalName: "도파민 디톡스", result: "MISS", reachLevel: null, scoreT: "0.1200", judgedAt: "2026-08-06 03:00", superseded: false },
  { trendItemId: "t3", canonicalName: "제로슈거 밀키트", result: "VOID", reachLevel: null, scoreT: null, judgedAt: "2026-08-05 03:00", superseded: true },
];
const fxImminent: ImminentItem[] = [
  { trendItemId: "t4", canonicalName: "가을 캠퍼스룩", firstSeenAt: "2026-07-31 09:00", deadline: "2026-08-14 09:00", daysLeft: 0, graceExtended: false },
  { trendItemId: "t5", canonicalName: "저속노화 식단", firstSeenAt: "2026-08-01 12:00", deadline: "2026-08-15 12:00", daysLeft: 1, graceExtended: false },
  { trendItemId: "t6", canonicalName: "역주행 챌린지", firstSeenAt: "2026-07-28 08:00", deadline: "2026-08-18 08:00", daysLeft: 4, graceExtended: true },
];
export const fxVerdicts: VerdictListResponse = { judged: fxJudged, imminent: fxImminent };

export const fxAudit: AuditEntry[] = [
  { id: 5012, actor: "김운영", role: "OPERATOR", action: "MERGE", targetType: "TREND", targetId: "#1204", createdAt: "2026-08-07 16:41" },
  { id: 5011, actor: "박관리", role: "ADMIN", action: "LEDGER_ADJ", targetType: "USER", targetId: "user_4410", createdAt: "2026-08-07 15:20" },
  { id: 5010, actor: "이감사", role: "AUDITOR", action: "PII_VIEW", targetType: "USER", targetId: "user_7731", createdAt: "2026-08-07 14:03" },
  { id: 5009, actor: "김운영", role: "OPERATOR", action: "VOID", targetType: "TREND", targetId: "#1188", createdAt: "2026-08-07 11:47" },
  { id: 5008, actor: "박관리", role: "ADMIN", action: "PARAM_APPLY", targetType: "DRAFT", targetId: "#16", createdAt: "2026-08-06 09:00" },
];
