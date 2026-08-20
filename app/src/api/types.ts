/**
 * OpenAPI 스키마 미러(backend/api-spec/openapi.yaml).
 * 최종적으로는 `npm run gen:api`(openapi-typescript)로 생성된 타입으로 대체한다(§2.4).
 */
import type { Stage } from "../theme";

export type Category = "MEME" | "PRODUCT" | "PERSON_CHANNEL" | "CHALLENGE" | "SLANG" | "ETC";
export type ReachLevel = "L1" | "L2" | "L3" | "L4";
export type Grade = "L0" | "L1" | "L2" | "L3" | "L4";
export type SubmissionStatus = "PENDING" | "HIT" | "MISS" | "VOID";

export interface TrendSummary {
  id: string;
  word: string;
  meaning: string;
  stage: Stage;
  stageLabel?: string;
  lifeText?: string;
  dText?: string;
  pathText?: string;
  reachedCount?: number;
  ageShort?: string | null;
}

export interface TrendList {
  items: TrendSummary[];
  nextCursor: string | null;
}

export interface PropagationStep {
  channel: string;
  date?: string | null;
  note?: string;
  reached: boolean;
}
export interface AgeBand {
  label: string;
  pct: number;
}
export interface TrendDetail extends TrendSummary {
  verdict?: string;
  verdictWhy?: string;
  reachLevel?: ReachLevel;
  origin?: string;
  example?: string;
  ageSentence?: string;
  ages?: AgeBand[];
  propagationPath?: PropagationStep[];
  voteCount?: string;
  watched?: boolean;
}

export interface SubmissionCreate {
  name: string;
  category: Category;
  platform: string;
  evidenceUrl: string;
  confidence: 10 | 30 | 50;
  disclosure: boolean;
  oneLine: string;
}
export interface SubmissionMine {
  id: string;
  word: string;
  status: SubmissionStatus;
  reachLevel?: ReachLevel;
  delta?: number | null;
  confidence: number;
  orderRank?: number | null;
  note?: string;
  judgeInDays?: number | null;
  createdAt: string;
}

export type GradeRequirementKind = "JUDGED_COUNT" | "TRUST_INDEX" | "ACTIVE_SCORE";
export interface GradeRequirement {
  kind: GradeRequirementKind;
  label: string;
  current: number;
  required: number;
  met: boolean;
  basis: string;
}
export interface GradeStatus {
  grade: Grade;
  gradeName: string;
  trustIndex: number;
  activeScore: number;
  judgedCount: number;
  nextGrade: string;
  requirements: GradeRequirement[];
  note: string;
}

export interface LedgerEntry {
  word: string;
  kind: "HIT" | "MISS" | "VOID" | "ADJ";
  delta: number;
  reason: string;
  createdAt: string;
}
export interface Ledger {
  items: LedgerEntry[];
  nextCursor: string | null;
}

export interface WatchItem {
  keyword: string;
  stage?: Stage;
  note?: string;
}
export interface MeSummary {
  streakDays: number;
  quotaUsed: number;
  quotaMax: number;
  voteHitRate: number;
  votesTotal: number;
  votesCorrect: number;
  totalRead: number;
}
export interface Preferences {
  categories: Category[];
  notifyHour: number;
}
export interface VoteResult {
  willTrend: boolean;
  voteCount: string;
}

export type ReportReason = "DEFAMATION" | "BUSINESS_INTERFERENCE" | "OTHER";
export type ReportStatus = "OPEN" | "EXPLAINING" | "DECIDED";
export type ReportDecisionType = "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE";

export interface ReportCreate {
  trendItemId: string;
  reason: ReportReason;
  detail?: string;
}
export interface ReportMine {
  id: string;
  trendItemId: string;
  status: ReportStatus;
  reason: ReportReason;
  decision: ReportDecisionType | null;
  decisionNote: string | null;
  createdAt: string;
  decidedAt: string | null;
}
export interface ReportReceived {
  id: string;
  trendItemId: string;
  reason: ReportReason;
  detail: string | null;
  explanationDeadline: string | null;
  explanationSubmitted: boolean;
  status: ReportStatus;
}
