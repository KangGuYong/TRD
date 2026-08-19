import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError } from "./client";
import type {
  GradeStatus, Ledger, MeSummary, Preferences, SubmissionCreate, SubmissionMine,
  TrendDetail, TrendList, VoteResult, WatchItem,
} from "./types";

export const qk = {
  daily: ["trends", "daily"] as const,
  search: (q: string) => ["trends", "search", q] as const,
  detail: (id: string) => ["trends", id] as const,
  meSummary: ["me", "summary"] as const,
  grade: ["me", "grade"] as const,
  ledger: ["me", "ledger"] as const,
  mySubs: ["submissions", "me"] as const,
  watch: ["me", "watch"] as const,
  prefs: ["me", "preferences"] as const,
};

/* ── 조회 ── */
export const useDailyTrends = () =>
  useQuery({ queryKey: qk.daily, queryFn: () => api.get<TrendList>("/v1/trends?daily=true") });

export const useSearchTrends = (q: string) =>
  useQuery({
    queryKey: qk.search(q),
    queryFn: () => api.get<TrendList>(`/v1/trends?q=${encodeURIComponent(q)}`),
    enabled: q.trim().length > 0,
  });

export const useTrendDetail = (id: string | null) =>
  useQuery({
    queryKey: qk.detail(id ?? ""),
    queryFn: () => api.get<TrendDetail>(`/v1/trends/${id}`),
    enabled: !!id,
  });

export const useMeSummary = () =>
  useQuery({ queryKey: qk.meSummary, queryFn: () => api.get<MeSummary>("/v1/me/summary") });

export const useMyGrade = () =>
  useQuery({ queryKey: qk.grade, queryFn: () => api.get<GradeStatus>("/v1/me/grade") });

export const useMyLedger = () =>
  useQuery({ queryKey: qk.ledger, queryFn: () => api.get<Ledger>("/v1/me/ledger") });

export const useMySubmissions = () =>
  useQuery({ queryKey: qk.mySubs, queryFn: () => api.get<SubmissionMine[]>("/v1/submissions/me") });

export const useWatch = () =>
  useQuery({ queryKey: qk.watch, queryFn: () => api.get<WatchItem[]>("/v1/me/watch") });

export const useMyPreferences = (enabled = true) =>
  useQuery({
    queryKey: qk.prefs,
    enabled,
    queryFn: async (): Promise<Preferences | null> => {
      try {
        return await api.get<Preferences>("/v1/me/preferences");
      } catch (e) {
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
  });

/* ── 변경 ── */
export const useSubmit = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: SubmissionCreate) => api.post<SubmissionMine>("/v1/submissions", body),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: qk.mySubs });
      qc.invalidateQueries({ queryKey: qk.meSummary });
    },
  });
};

export const useVote = (trendId: string) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (willTrend: boolean) =>
      api.post<VoteResult>(`/v1/trends/${trendId}/vote`, { willTrend }),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.detail(trendId) }),
  });
};

export const useToggleWatch = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ keyword, on }: { keyword: string; on: boolean }) =>
      on ? api.post("/v1/me/watch", { keyword }) : api.del(`/v1/me/watch/${encodeURIComponent(keyword)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.watch }),
  });
};

export const useEndorse = (trendId: string) => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post(`/v1/trends/${trendId}/endorse`),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.mySubs }),
  });
};

export const useSavePreferences = () => {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Preferences) => api.put<Preferences>("/v1/me/preferences", body),
    onSuccess: () => qc.invalidateQueries({ queryKey: qk.prefs }),
  });
};
