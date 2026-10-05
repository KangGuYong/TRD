/** OpenAPI /admin 스키마 미러(backend/api-spec/openapi.yaml). gen:api로 대체 가능. */

export interface QueueSummary {
  slaBreaches: number;
  queues: { id: string; name: string; count: number; oldest: string; slaExceeded: boolean; available: boolean }[];
  alerts: { title: string; detail: string }[];
  seedRatio: number;
  judgedToday: number;
  imminent24h: number;
}

export interface SubmissionDetail {
  handle: string;
  rawInput: string;
  oneLine: string;
  evidenceUrl: string;
  createdAt: string;
}
export interface MergeCandidate {
  id: string;
  similarity: number;
  newName: string;
  oldName: string;
  oldClusterId: string;
  ago: string;
  newRows: { k: string; v: string }[];
  oldRows: { k: string; v: string }[];
  orderPreview: string[];
  newSubmissions: SubmissionDetail[];
  oldSubmissions: SubmissionDetail[];
}

export interface OrderEntry {
  handle: string;
  rankBefore: number | null;
  /** 시딩이면 null */
  rankAfter: number | null;
  seed: boolean;
}

export interface MergePreview {
  newCanonicalName: string;
  orderRank: OrderEntry[];
  firstSeenAtBefore: string;
  firstSeenAtAfter: string;
  deadlineBefore: string;
  deadlineAfter: string;
  /** 병합으로 관측 기간이 줄어 최소 3일 보장이 적용됨 */
  deadlineGuarded: boolean;
  dedupVoidedHandles: string[];
  /** 중복 VOID로 제보권이 돌아가는 제보자(시딩 제외) */
  quotaRefundHandles: string[];
}

export interface MergeDecisionResponse {
  queueId: string;
  decision: "MERGE" | "SEPARATE" | "VOID";
  status: "MERGED" | "SKIPPED" | "VOIDED";
  survivorId: string | null;
  loserId: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  replayed: boolean;
}

export interface SimulationSummary {
  changed: number;
  total: number;
  missToHit: number;
  hitToMiss: number;
  reachChanged: number;
}
export type IndependenceMode = "OFF" | "DEVICE" | "DEVICE_OR_IP";
export interface DraftValues {
  targetFloor: number; targetRatio: number; activeWindowDays: number; hitThreshold: number;
  persistenceFloor: number; persistenceFullDays: number; diversityFloor: number; diversityFullPlatforms: number;
  independenceMode: IndependenceMode;
}
export interface TBreakdown {
  accounts: number; independent: number; target: number; activeSubmitters: number | null; ratio: number;
  activeDays: number; persistenceFullDays: number; persistence: number;
  platforms: number; diversityFullPlatforms: number; diversityApplied: boolean; diversity: number; t: number;
}
export interface BacktestOutcome { result: "HIT" | "MISS"; reach: string | null; breakdown: TBreakdown; explain: string }
export interface BacktestSide {
  confusion: { tp: number; fp: number; fn: number; tn: number };
  precision: number | null; recall: number | null; reachAgreement: number | null; reachCompared: number;
}
export interface BacktestCaseRow {
  caseId: string; title: string; label: "HIT" | "MISS"; labelReach: string | null;
  current: BacktestOutcome; draft: BacktestOutcome; changed: boolean;
}
export interface BacktestResult {
  datasetId: string; datasetName: string; sha256: string; caseCount: number; ranAt: string;
  report: { caseCount: number; current: BacktestSide; draft: BacktestSide; changedCount: number; rows: BacktestCaseRow[] };
}
export interface BacktestDatasetSummary {
  id: string; name: string; caseCount: number; sha256: string; uploadedBy: string; createdAt: string;
}
export interface ParameterDraftView {
  draftId: string | null;
  status: "DRAFT" | "REVIEW" | "NONE";
  values: DraftValues;
  current: DraftValues;
  simResult: SimulationSummary | null;
  backtestResult: BacktestResult | null;
}

export interface LedgerRow {
  id: string;
  createdAt: string;
  kind: "HIT" | "MISS" | "VOID" | "ADJ";
  delta: number;
  reason: string;
  trendItemName: string | null;
  verdictId: string | null;
  approvedBy: string | null;
  approvalId: string | null;
}
export interface AdminUserDetail {
  id: string;
  handle: string;
  status: string;
  joinedAt: string;
  grade: string;
  gradeComputedAt: string | null;
  trustIndex: number;
  activeScore: number;
  judgedCount: number;
  hitInWindow: number;
  missInWindow: number;
  basis: string[];
  abuseFlagCount: number;
  ledger: LedgerRow[];
}
export interface UserHit { id: string; handle: string; grade: string; joinedAt: string }

/** 승인 게이트를 지나는 작업의 결과 — 200/201 APPLIED, 202 PENDING_APPROVAL. */
export interface ActionResult {
  status: "APPLIED" | "PENDING_APPROVAL";
  approvalRequestId: string | null;
  adjTotal?: number | null;
  amount?: number | null;
}

export interface AuditEntry {
  id: number;
  actor: string;
  role: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  detail: Record<string, unknown>;
  createdAt: string;
}
export interface AuditPage { items: AuditEntry[]; nextBeforeId: number | null }
export interface AuditFilter { action?: string; targetId?: string; from?: string; to?: string }

export interface AdminAccountSummary {
  id: string;
  loginId: string;
  displayName: string;
  role: string;
  lastLoginAt: string | null;
  disabledAt: string | null;
  createdAt: string;
  activatedAt: string | null;
  approverSince: string | null;
  pendingApproval: boolean;
}
export interface CreateAccountResponse {
  status: "CREATED_BOOTSTRAP" | "PENDING_APPROVAL";
  account: AdminAccountSummary;
  approvalRequestId: string | null;
}

export interface JudgedItem {
  trendItemId: string;
  canonicalName: string;
  result: string;
  reachLevel: string | null;
  scoreT: string | null;
  judgedAt: string | null;
  superseded: boolean;
  tExplain: string | null;
}
export interface ImminentItem {
  trendItemId: string;
  canonicalName: string;
  firstSeenAt: string;
  deadline: string;
  daysLeft: number;
  graceExtended: boolean;
}
export interface VerdictListResponse {
  judged: JudgedItem[];
  imminent: ImminentItem[];
}

export interface SeedSubmissionRequest {
  name: string;
  category: "MEME" | "PRODUCT" | "PERSON_CHANNEL" | "CHALLENGE" | "SLANG" | "ETC";
  evidenceUrl: string;
  confidence: 10 | 30 | 50;
  oneLine: string;
}
export interface SeedSubmissionResult {
  submissionId: string;
  canonicalName: string;
  trendItemId: string;
}
export interface SeedAccuracyRow {
  operatorName: string;
  hit: number;
  miss: number;
  judged: number;
  trustIndex: number;
}

export interface ClusterMergeResult {
  candidates: number;
  autoMerged: number;
  queued: number;
  separated: number;
  /** 판정된 항목과 닮아 병합하지 않고 기록만 함 */
  skippedJudged: number;
  /** 판정·다른 병합과 경합해 다음 실행에서 다시 봄 */
  deferred: number;
  failed: number;
}

export interface TrendItemSummary {
  id: string; canonicalName: string; category: string; state: string;
  firstSeenAt: string; submitterCount: number; currentResult: string | null;
}
export interface SubmissionRow {
  submissionId: string; userHandle: string; orderRank: number | null;
  confidence: number; submitterTi: number | null; createdAt: string;
  platform: string; oneLine: string; evidenceUrl: string;
}
export interface TrendItemDetail {
  id: string; canonicalName: string; category: string; state: string;
  firstSeenAt: string; deadline: string; daysLeft: number; graceExtended: boolean;
  distinctSubmitters: number; distinctPlatforms: number; endorseCount: number;
  currentResult: string | null; currentReachLevel: string | null; currentScoreT: string | null; currentJudgedAt: string | null;
  previewResult: string | null; previewReachLevel: string | null; previewScoreT: string | null; previewExplain: string | null;
  submissions: SubmissionRow[];
}

export interface ApprovalRequestView {
  id: string;
  actionType: "PARAM_APPLY" | "VERDICT_REJUDGE" | "ITEM_VOID" | "LEDGER_ADJ" | "ACCOUNT_CREATE" | "ACCOUNT_ROLE_CHANGE";
  targetRef: string;
  requestedBy: string;
  requestedByName: string;
  approvals: number;
  requiredApprovals: number;
  status: "PENDING" | "APPROVED" | "REJECTED" | "EXECUTED";
  reason: string | null;
  summary: string;
  createdAt: string;
  resolvedAt: string | null;
}

export interface ReportSubmissionCandidate {
  submissionId: string;
  handle: string;
  rawInput: string;
  oneLine: string;
  evidenceUrl: string;
  createdAt: string;
}
export interface ReportQueueItem {
  id: string;
  trendItemId: string;
  reason: "DEFAMATION" | "BUSINESS_INTERFERENCE" | "OTHER";
  detail: string | null;
  status: "OPEN" | "EXPLAINING" | "DECIDED";
  submissionId: string | null;
  explanationDeadline: string | null;
  explanationText: string | null;
  decision: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE" | null;
  decisionNote: string | null;
  autoHiddenAt: string | null;
  createdAt: string;
}
