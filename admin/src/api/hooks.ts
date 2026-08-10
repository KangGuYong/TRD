import { useQuery } from "@tanstack/react-query";
import { api, USE_FIXTURES } from "./client";
import * as fx from "../fixtures";
import type { AdminUserDetail, AuditEntry, MergeCandidate, QueueSummary } from "./types";

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
