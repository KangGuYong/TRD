import React, { createContext, useCallback, useContext, useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { useAuth } from "./auth";

/**
 * 완독 추적. 서버(GET/POST /v1/me/reads)가 진실이다 — 로그인 시 서버 기록을 불러오고,
 * 카드를 읽으면 즉시 화면에 반영(로컬)하면서 서버에도 기록한다. 새로고침/재로그인해도 유지된다.
 */
const ReadsContext = createContext<{ read: Set<string>; markRead: (id: string) => void }>({
  read: new Set(),
  markRead: () => {},
});

export function ReadsProvider({ children }: { children: React.ReactNode }) {
  const { user, initializing } = useAuth();
  const serverReads = useQuery({
    queryKey: ["me", "reads"],
    enabled: !!user && !initializing,
    queryFn: () => api.get<string[]>("/v1/me/reads"),
  });
  const [localRead, setLocalRead] = useState<Set<string>>(new Set());

  const read = useMemo(() => {
    const merged = new Set(serverReads.data ?? []);
    localRead.forEach((id) => merged.add(id));
    return merged;
  }, [serverReads.data, localRead]);

  const markRead = useCallback((id: string) => {
    setLocalRead((prev) => (prev.has(id) ? prev : new Set(prev).add(id)));
    api.post("/v1/me/reads", { trendId: id }).catch(() => {}); // best-effort — 로컬 표시는 이미 됨
  }, []);

  const value = useMemo(() => ({ read, markRead }), [read, markRead]);
  return <ReadsContext.Provider value={value}>{children}</ReadsContext.Provider>;
}

export const useReads = () => useContext(ReadsContext);
