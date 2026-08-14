/** OpenAPI /admin 스키마 미러(backend/api-spec/openapi.yaml). gen:api로 대체 가능. */

export interface QueueSummary {
  slaBreaches: number;
  queues: { id: string; name: string; count: number; oldest: string; slaExceeded: boolean }[];
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
  rankAfter: number;
}
export interface MergePreview {
  newCanonicalName: string;
  orderRank: OrderEntry[];
  firstSeenAtBefore: string;
  firstSeenAtAfter: string;
  baselineShifted: boolean;
  dedupVoidedHandles: string[];
}

export interface SimulationSummary {
  changed: number;
  total: number;
  missToHit: number;
  hitToMiss: number;
  reachChanged: number;
}
export interface ParameterDraftView {
  draftId: string;
  status: "DRAFT" | "REVIEW";
  submitterTarget: number;
  currentSubmitterTarget: number;
  hitThreshold: number;
  currentHitThreshold: number;
  simResult: SimulationSummary | null;
}

export interface LedgerEntry {
  date: string;
  kind: "HIT" | "MISS" | "VOID" | "ADJ";
  delta: string;
  reason: string;
}
export interface AdminUserDetail {
  userId: string;
  grade: string;
  activeScore: number;
  trustIndex: number;
  judgedCount: number;
  hit: number;
  miss: number;
  flags: number;
  joinedAt: string;
  ledger: LedgerEntry[];
}

export interface AuditEntry {
  id: number;
  actor: string;
  role: string;
  action: string;
  targetType: string | null;
  targetId: string | null;
  createdAt: string;
}

export interface AdminAccountSummary {
  id: string;
  loginId: string;
  displayName: string;
  role: string;
  lastLoginAt: string | null;
  disabledAt: string | null;
  createdAt: string;
}

export interface JudgedItem {
  trendItemId: string;
  canonicalName: string;
  result: string;
  reachLevel: string | null;
  scoreT: string | null;
  judgedAt: string | null;
  superseded: boolean;
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
