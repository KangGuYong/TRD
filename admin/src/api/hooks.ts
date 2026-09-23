import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, USE_FIXTURES } from "./client";
import * as fx from "../fixtures";
import type { ActionResult, AdminAccountSummary, ApprovalRequestView, AdminUserDetail, AuditEntry, AuditFilter, AuditPage, ClusterMergeResult, CreateAccountResponse, MergeCandidate, MergeDecisionResponse, MergePreview, ParameterDraftView, QueueSummary, ReportQueueItem, ReportSubmissionCandidate, SeedAccuracyRow, SeedSubmissionRequest, SeedSubmissionResult, TrendItemSummary, TrendItemDetail, UserHit, VerdictListResponse } from "./types";

/** 픽스처 on이면 즉시 픽스처, off면 실 API. 동일 훅으로 백엔드 전환. */
function useData<T>(key: unknown[], path: string, fixture: T) {
  return useQuery({
    queryKey: key,
    queryFn: USE_FIXTURES ? async () => fixture : () => api.get<T>(path),
  });
}

export const useQueueSummary = () =>
  useData<QueueSummary>(["admin", "queues"], "/admin/queues/summary", fx.fxQueueSummary);

export const useMergeQueue = () =>
  useData<MergeCandidate[]>(["admin", "merge-queue"], "/admin/merge-queue", fx.fxMergeQueue);

export const useAdminUserSearch = (handle: string) =>
  useQuery({
    queryKey: ["admin", "user-search", handle],
    enabled: !USE_FIXTURES && handle.trim().length >= 2,
    queryFn: () => api.get<UserHit[]>(`/admin/users?handle=${encodeURIComponent(handle.trim())}`),
  });

export const useAdminUser = (id: string | null) =>
  useQuery({
    queryKey: ["admin", "user", id],
    enabled: USE_FIXTURES || id !== null,
    queryFn: USE_FIXTURES ? async () => fx.fxUser : () => api.get<AdminUserDetail>(`/admin/users/${id}`),
  });

export const adjustLedger = (userId: string, amount: number, reason: string) =>
  api.post<ActionResult>(`/admin/users/${userId}/ledger-adjustments`, { amount, reason });

export const useAuditLog = (filter: AuditFilter, beforeId: number | null) => {
  const params = new URLSearchParams();
  Object.entries(filter).forEach(([k, v]) => { if (v) params.set(k, v); });
  if (beforeId !== null) params.set("beforeId", String(beforeId));
  return useData<AuditPage>(["admin", "audit", filter, beforeId], `/admin/audit-log?${params}`, fx.fxAudit);
};

export const useAdminAccounts = () =>
  useData<AdminAccountSummary[]>(["admin", "accounts"], "/admin/accounts", fx.fxAdminAccounts);

export const createAdminAccount = (req: { loginId: string; displayName: string; role: string; password: string }) =>
  api.post<CreateAccountResponse>("/admin/accounts", req);

export const changeAdminRole = (id: string, role: string, reason: string) =>
  api.post<{ approvalRequestId: string }>(`/admin/accounts/${id}/role`, { role, reason });

export const changeMyPassword = (current: string, next: string) =>
  api.post<void>("/admin/me/password", { current, next });

export const setAdminAccountDisabled = (id: string, disabled: boolean) =>
  api.post<AdminAccountSummary>(`/admin/accounts/${id}/${disabled ? "disable" : "enable"}`);

/**
 * 병합 큐 결정. 결정마다 멱등키를 하나 만들고, 네트워크 오류·5xx일 때만 같은 키로 한 번 재시도한다 —
 * 서버가 이미 처리했으면 같은 결과를 돌려준다(SP2 K5). 4xx는 서버가 판단을 끝낸 것이라 재시도하지 않는다.
 */
export async function decideMergeCandidate(
  id: string,
  action: "merge" | "separate" | "void",
  reason: string,
): Promise<MergeDecisionResponse> {
  const key = crypto.randomUUID();
  const send = () =>
    api.post<MergeDecisionResponse>(`/admin/merge-queue/${id}/${action}`, { reason }, { "Idempotency-Key": key });
  try {
    return await send();
  } catch (e) {
    if (e instanceof ApiError && e.status < 500) throw e;
    return await send();
  }
}

export const fetchMergePreview = (id: string) =>
  api.get<MergePreview>(`/admin/merge-queue/${id}/preview`);

export const useParamDraft = () =>
  useData<ParameterDraftView>(["admin", "param-draft"], "/admin/params/draft", fx.fxParamDraft);

export const updateParamDraft = (body: { submitterTarget: number; hitThreshold: number }) =>
  api.put<ParameterDraftView>("/admin/params/draft", body);

export const simulateParamDraft = () =>
  api.post<ParameterDraftView>("/admin/params/draft/simulate");

export const requestParamApproval = (reason: string) =>
  api.post<ParameterDraftView>("/admin/params/draft/request-approval", { reason });

export const useVerdicts = () =>
  useData<VerdictListResponse>(["admin", "verdicts"], "/admin/verdicts", fx.fxVerdicts);

export const voidVerdict = (trendItemId: string, reason: string) =>
  api.post<ActionResult>(`/admin/verdicts/${trendItemId}/void`, { reason });

export const rejudgeVerdict = (trendItemId: string, reason: string) =>
  api.post<ActionResult>(`/admin/verdicts/${trendItemId}/rejudge`, { reason });

export const extendVerdictGrace = (trendItemId: string, days: number, reason: string) =>
  api.post<void>(`/admin/verdicts/${trendItemId}/extend-grace`, { days, reason });

export const useSeedAccuracy = () =>
  useData<SeedAccuracyRow[]>(["admin", "seed-accuracy"], "/admin/seed/accuracy", fx.fxSeedAccuracy);

export const registerSeed = (req: SeedSubmissionRequest) =>
  api.post<SeedSubmissionResult>("/admin/seed/submissions", req);

export const triggerClusterMerge = () =>
  api.post<ClusterMergeResult>("/admin/batch-jobs/cluster-merge/run");

export const useTrendItems = () =>
  useData<TrendItemSummary[]>(["admin", "trend-items"], "/admin/trend-items", fx.fxTrendItems);

export const fetchTrendItemDetail = (id: string) =>
  api.get<TrendItemDetail>(`/admin/trend-items/${id}`);

export const useApprovals = () =>
  useData<ApprovalRequestView[]>(["admin", "approvals"], "/admin/approvals", fx.fxApprovals);

export const approveApproval = (id: string) =>
  api.post<ApprovalRequestView>(`/admin/approvals/${id}/approve`);

export const rejectApproval = (id: string, reason: string) =>
  api.post<ApprovalRequestView>(`/admin/approvals/${id}/reject`, { reason });

export const useReportQueue = () =>
  useData<ReportQueueItem[]>(["admin", "reports"], "/admin/reports", fx.fxReportQueue);

export const fetchReportSubmissionCandidates = (reportId: string) =>
  api.get<ReportSubmissionCandidate[]>(`/admin/reports/${reportId}/submissions`);

export const hideReport = (reportId: string, submissionId: string, note?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/hide`, { submissionId, note });

export const requestExplanation = (reportId: string, submissionId: string, note?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/request-explanation`, { submissionId, note });

export const decideReport = (reportId: string, decision: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE", note: string, newCanonicalName?: string) =>
  api.post<ReportQueueItem>(`/admin/reports/${reportId}/decide`, { decision, note, newCanonicalName });
