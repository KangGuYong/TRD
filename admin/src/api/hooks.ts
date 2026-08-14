import { useQuery } from "@tanstack/react-query";
import { api, USE_FIXTURES } from "./client";
import * as fx from "../fixtures";
import type { AdminAccountSummary, AdminUserDetail, AuditEntry, MergeCandidate, MergePreview, QueueSummary, VerdictListResponse } from "./types";

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

export const useAdminUser = (id: string) =>
  useData<AdminUserDetail>(["admin", "user", id], `/admin/users/${id}`, fx.fxUser);

export const useAuditLog = () =>
  useData<AuditEntry[]>(["admin", "audit"], "/admin/audit-log", fx.fxAudit);

export const useAdminAccounts = () =>
  useData<AdminAccountSummary[]>(["admin", "accounts"], "/admin/accounts", fx.fxAdminAccounts);

/** fixture 모드에서는 백엔드가 없으므로 실 API를 호출하지 않는다 — 화면단에서 로컬로만 시뮬레이션. */
export const createAdminAccount = (req: { loginId: string; displayName: string; role: string; password: string }) =>
  api.post<AdminAccountSummary>("/admin/accounts", req);

export const setAdminAccountDisabled = (id: string, disabled: boolean) =>
  api.post<AdminAccountSummary>(`/admin/accounts/${id}/${disabled ? "disable" : "enable"}`);

export const decideMergeCandidate = (id: string, action: "merge" | "separate" | "void", reason: string) =>
  api.post<void>(`/admin/merge-queue/${id}/${action}`, { reason });

export const fetchMergePreview = (id: string) =>
  api.get<MergePreview>(`/admin/merge-queue/${id}/preview`);

export const useVerdicts = () =>
  useData<VerdictListResponse>(["admin", "verdicts"], "/admin/verdicts", fx.fxVerdicts);

export const voidVerdict = (trendItemId: string, reason: string) =>
  api.post<void>(`/admin/verdicts/${trendItemId}/void`, { reason });

export const rejudgeVerdict = (trendItemId: string, reason: string) =>
  api.post<void>(`/admin/verdicts/${trendItemId}/rejudge`, { reason });

export const extendVerdictGrace = (trendItemId: string, days: number, reason: string) =>
  api.post<void>(`/admin/verdicts/${trendItemId}/extend-grace`, { days, reason });
