/** dev 시연용 데이터. 관리자 콘솔.dc.html 예시를 반영. 실 API로 대체됨. */
import type { AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditPage, BacktestDatasetSummary, ClusterMergeResult, DraftValues, ImminentItem, JudgedItem, MergeCandidate, MergePreview, ParameterDraftView, QueueSummary, ReportQueueItem, ReportSubmissionCandidate, SeedAccuracyRow, SimulationSummary, TrendItemDetail, TrendItemSummary, VerdictListResponse } from "./api/types";

export const fxQueueSummary: QueueSummary = {
  slaBreaches: 1,
  queues: [
    { id: "ADM-100", name: "병합 검수", count: 24, oldest: "4h", slaExceeded: false, available: true },
    { id: "ADM-300", name: "어뷰징", count: 0, oldest: "-", slaExceeded: false, available: false },
    { id: "ADM-400", name: "이의 제기", count: 0, oldest: "-", slaExceeded: false, available: false },
    { id: "ADM-410", name: "신고 콘텐츠", count: 2, oldest: "5h", slaExceeded: true, available: true },
  ],
  alerts: [
    { title: "신고 콘텐츠 SLA 초과 1건", detail: "4시간 기준 초과 — 자동 임시 비공개는 미구현, 수동 처리 필요" },
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
    { handle: "user_4410", rankBefore: 1, rankAfter: 1, seed: false },
    { handle: "user_1122", rankBefore: 2, rankAfter: 2, seed: false },
    { handle: "user_8821", rankBefore: 1, rankAfter: 3, seed: false },
    { handle: "seed_ops", rankBefore: null, rankAfter: null, seed: true },
  ],
  firstSeenAtBefore: "2026-08-11 09:00",
  firstSeenAtAfter: "2026-08-10 21:00",
  deadlineBefore: "2026-08-25 09:00",
  deadlineAfter: "2026-08-27 14:00",
  deadlineGuarded: true,
  dedupVoidedHandles: ["user_1122"],
  quotaRefundHandles: ["user_1122"],
};

const NEUTRAL: DraftValues = {
  targetFloor: 20, targetRatio: 0, activeWindowDays: 28, hitThreshold: 0.2,
  persistenceFloor: 1, persistenceFullDays: 5, diversityFloor: 1, diversityFullPlatforms: 3, independenceMode: "OFF",
};

export const fxParamDraft: ParameterDraftView = {
  draftId: "pd-17",
  status: "DRAFT",
  values: NEUTRAL,
  current: NEUTRAL,
  simResult: null,
  backtestResult: null,
};

export const fxBacktestDatasets: BacktestDatasetSummary[] = [];

export const fxClusterMergeResult: ClusterMergeResult = {
  candidates: 8, autoMerged: 1, queued: 2, separated: 4, skippedJudged: 1, deferred: 0, failed: 0,
};

export const fxUser: AdminUserDetail = {
  id: "user_4410",
  handle: "user_4410",
  status: "ACTIVE",
  joinedAt: "2026-02-11",
  grade: "L3 분석가",
  gradeComputedAt: "2026-08-04 00:00",
  activeScore: 512,
  trustIndex: 0.63,
  judgedCount: 47,
  hitInWindow: 29,
  missInWindow: 18,
  basis: ["TI = (29 + 2) / (29 + 18 + 5) = 0.63 (180일 기준)", "AS = 최근 원장 행 감쇠합"],
  abuseFlagCount: 0,
  ledger: [
    { id: "l1", createdAt: "2026-08-05", kind: "HIT", delta: 75.0, reason: "#1204 order1 c50 m1.0", trendItemName: "새싹 챌린지", verdictId: "v-1204", approvedBy: null, approvalId: null },
    { id: "l2", createdAt: "2026-08-01", kind: "MISS", delta: -15.0, reason: "#1188 c30", trendItemName: "제로슈거 밀키트", verdictId: "v-1188", approvedBy: null, approvalId: null },
    { id: "l3", createdAt: "2026-07-28", kind: "ADJ", delta: 12.0, reason: "판정 재계산 보정", trendItemName: null, verdictId: null, approvedBy: "박관리", approvalId: "ap-9" },
    { id: "l4", createdAt: "2026-07-21", kind: "HIT", delta: 60.0, reason: "#1150 order1 c30 m2.0", trendItemName: "가을 캠퍼스룩", verdictId: "v-1150", approvedBy: null, approvalId: null },
  ],
};

export const fxAdminAccounts: AdminAccountSummary[] = [
  { id: "a1", loginId: "admin", displayName: "박관리", role: "ADMIN", lastLoginAt: "2026-08-07 09:12", disabledAt: null, createdAt: "2026-07-01 10:00", activatedAt: "2026-07-01 10:00", approverSince: "2026-07-01 10:00", pendingApproval: false },
  { id: "a2", loginId: "op_kim", displayName: "김운영", role: "OPERATOR", lastLoginAt: "2026-08-07 16:41", disabledAt: null, createdAt: "2026-07-05 11:30", activatedAt: "2026-07-05 11:30", approverSince: "2026-07-05 11:30", pendingApproval: false },
  { id: "a3", loginId: "auditor_lee", displayName: "이감사", role: "AUDITOR", lastLoginAt: "2026-08-06 14:03", disabledAt: null, createdAt: "2026-07-10 09:00", activatedAt: "2026-07-10 09:00", approverSince: "2026-07-10 09:00", pendingApproval: false },
  { id: "a4", loginId: "reviewer_old", displayName: "퇴사자", role: "REVIEWER", lastLoginAt: "2026-06-01 09:00", disabledAt: "2026-06-15 00:00", createdAt: "2026-05-01 09:00", activatedAt: "2026-05-01 09:00", approverSince: "2026-05-01 09:00", pendingApproval: false },
];

export const fxApprovals: ApprovalRequestView[] = [
  { id: "ap1", actionType: "PARAM_APPLY", targetRef: "pd-17", requestedBy: "admin-fx-1", requestedByName: "김운영", approvals: 0, requiredApprovals: 1, status: "PENDING", reason: "O1 백테스트 결과 반영 — submitterTarget 20→18", summary: "파라미터 적용: submitterTarget 20→18", createdAt: "2026-08-07 11:20", resolvedAt: null },
  { id: "ap2", actionType: "LEDGER_ADJ", targetRef: "user_4410", requestedBy: "admin-fx-2", requestedByName: "박관리", approvals: 0, requiredApprovals: 1, status: "PENDING", reason: "hitThreshold 0.20→0.22 보정", summary: "원장 조정: user_4410 +150.0", createdAt: "2026-08-05 09:40", resolvedAt: null },
];

export const fxReportQueue: ReportQueueItem[] = [
  {
    id: "rp-1", trendItemId: "ti-1", reason: "DEFAMATION", detail: "특정 인물을 비하하는 표현이 포함돼 있습니다",
    status: "OPEN", submissionId: null, explanationDeadline: null, explanationText: null,
    decision: null, decisionNote: null, autoHiddenAt: null, createdAt: "2026-08-19 14:20",
  },
  {
    id: "rp-2", trendItemId: "ti-3", reason: "BUSINESS_INTERFERENCE", detail: "경쟁사 비방성 제보로 의심됩니다",
    status: "EXPLAINING", submissionId: "sub-1", explanationDeadline: "2026-08-21 09:12", explanationText: null,
    decision: null, decisionNote: null, autoHiddenAt: "2026-08-18 13:00", createdAt: "2026-08-18 09:00",
  },
];

const fxJudged: JudgedItem[] = [
  { trendItemId: "t1", canonicalName: "탕후루 챌린지", result: "HIT", reachLevel: "L2", scoreT: "0.5500", judgedAt: "2026-08-07 03:00", superseded: false, tExplain: "제보자 11/20 × 지속성 1.00 × 다양성 1.00" },
  { trendItemId: "t2", canonicalName: "도파민 디톡스", result: "MISS", reachLevel: null, scoreT: "0.1200", judgedAt: "2026-08-06 03:00", superseded: false, tExplain: "제보자 3/20 × 지속성 0.80 × 다양성 1.00" },
  { trendItemId: "t3", canonicalName: "제로슈거 밀키트", result: "VOID", reachLevel: null, scoreT: null, judgedAt: "2026-08-05 03:00", superseded: true, tExplain: null },
];
const fxImminent: ImminentItem[] = [
  { trendItemId: "t4", canonicalName: "가을 캠퍼스룩", firstSeenAt: "2026-07-31 09:00", deadline: "2026-08-14 09:00", daysLeft: 0, graceExtended: false },
  { trendItemId: "t5", canonicalName: "저속노화 식단", firstSeenAt: "2026-08-01 12:00", deadline: "2026-08-15 12:00", daysLeft: 1, graceExtended: false },
  { trendItemId: "t6", canonicalName: "역주행 챌린지", firstSeenAt: "2026-07-28 08:00", deadline: "2026-08-18 08:00", daysLeft: 4, graceExtended: true },
];
export const fxVerdicts: VerdictListResponse = { judged: fxJudged, imminent: fxImminent };

export const fxAudit: AuditPage = {
  items: [
    { id: 5012, actor: "김운영", role: "OPERATOR", action: "MERGE", targetType: "TREND", targetId: "#1204", detail: {}, createdAt: "2026-08-07 16:41" },
    { id: 5011, actor: "박관리", role: "ADMIN", action: "LEDGER_ADJ", targetType: "USER", targetId: "user_4410", detail: {}, createdAt: "2026-08-07 15:20" },
    { id: 5010, actor: "박관리", role: "ADMIN", action: "LEDGER_ADJ", targetType: "USER", targetId: "user_7731", detail: {}, createdAt: "2026-08-07 14:03" },
    { id: 5009, actor: "김운영", role: "OPERATOR", action: "VOID", targetType: "TREND", targetId: "#1188", detail: {}, createdAt: "2026-08-07 11:47" },
    { id: 5008, actor: "박관리", role: "ADMIN", action: "PARAM_APPLY", targetType: "DRAFT", targetId: "#16", detail: {}, createdAt: "2026-08-06 09:00" },
  ],
  nextBeforeId: null,
};

export const fxSeedAccuracy: SeedAccuracyRow[] = [
  { operatorName: "김운영", hit: 14, miss: 3, judged: 17, trustIndex: 0.7273 },
  { operatorName: "이시딩", hit: 6, miss: 6, judged: 12, trustIndex: 0.4444 },
  { operatorName: "박담당", hit: 1, miss: 4, judged: 5, trustIndex: 0.3 },
];

export const fxTrendItems: TrendItemSummary[] = [
  { id: "ti-1", canonicalName: "○○ 챌린지", category: "CHALLENGE", state: "PENDING", firstSeenAt: "2026-08-15 09:12", submitterCount: 12, currentResult: null },
  { id: "ti-2", canonicalName: "△△ 밈", category: "MEME", state: "JUDGING", firstSeenAt: "2026-08-10 14:30", submitterCount: 20, currentResult: null },
  { id: "ti-3", canonicalName: "□□ 신제품", category: "PRODUCT", state: "RESOLVED", firstSeenAt: "2026-07-28 11:00", submitterCount: 25, currentResult: "HIT" },
  { id: "ti-4", canonicalName: "◇◇ 밈", category: "MEME", state: "VOID", firstSeenAt: "2026-07-20 08:45", submitterCount: 3, currentResult: "VOID" },
  { id: "ti-5", canonicalName: "☆☆ 챌린지", category: "CHALLENGE", state: "RESOLVED", firstSeenAt: "2026-07-15 16:20", submitterCount: 8, currentResult: "MISS" },
];

export const fxTrendItemDetail: TrendItemDetail = {
  id: "ti-1", canonicalName: "○○ 챌린지", category: "CHALLENGE", state: "PENDING",
  firstSeenAt: "2026-08-15 09:12", deadline: "2026-08-29 09:12", daysLeft: 9, graceExtended: false,
  distinctSubmitters: 12, distinctPlatforms: 3, endorseCount: 6,
  currentResult: null, currentReachLevel: null, currentScoreT: null, currentJudgedAt: null,
  previewResult: "HIT", previewReachLevel: "L3", previewScoreT: "0.6000", previewExplain: "제보자 12/20 × 지속성 1.00 × 다양성 1.00",
  submissions: [
    { submissionId: "sub-1", userHandle: "user_4410", orderRank: 1, confidence: 50, submitterTi: 0.63, createdAt: "2026-08-15 09:12",
      platform: "인스타", oneLine: "친구들 사이에서 다들 이걸로 챌린지 영상 찍는 중", evidenceUrl: "https://instagram.com/p/example1" },
    { submissionId: "sub-2", userHandle: "user_8821", orderRank: 2, confidence: 30, submitterTi: 0.51, createdAt: "2026-08-16 10:05",
      platform: "X", oneLine: "타임라인에 관련 게시물이 갑자기 늘었음", evidenceUrl: "https://x.com/example/status/123" },
    { submissionId: "sub-3", userHandle: "user_0092", orderRank: 3, confidence: 10, submitterTi: 0.38, createdAt: "2026-08-17 18:40",
      platform: "디시", oneLine: "갤러리에도 관련 글이 올라오기 시작함", evidenceUrl: "https://gall.dcinside.com/example" },
  ],
};

export const fxReportSubmissionCandidates: ReportSubmissionCandidate[] = [
  { submissionId: "sub-1", handle: "user_8821", rawInput: "새싹챌린지", oneLine: "요즘 카페에서 새싹 올린 음료 챌린지 유행 중", evidenceUrl: "https://instagram.com/p/example1", createdAt: "2026-08-15 09:12" },
  { submissionId: "sub-2", handle: "user_0092", rawInput: "새싹 올리기 챌린지", oneLine: "인스타에서 새싹 사진 릴레이", evidenceUrl: "https://instagram.com/p/example2", createdAt: "2026-08-16 10:05" },
];
