import React, { createContext, useCallback, useContext, useMemo, useState } from "react";
import { api } from "../api/client";

/** 완독 스트릭용 읽음 추적(인메모리). 카드 열람 시 서버에도 best-effort 기록. */
const ReadsContext = createContext<{ read: Set<string>; markRead: (id: string) => void }>({
  read: new Set(),
  markRead: () => {},
});

export function ReadsProvider({ children }: { children: React.ReactNode }) {
  const [read, setRead] = useState<Set<string>>(new Set());
  const markRead = useCallback((id: string) => {
    setRead((prev) => {
      if (prev.has(id)) return prev;
      const next = new Set(prev);
      next.add(id);
      api.post("/v1/me/reads", { trendId: id }).catch(() => {}); // best-effort
      return next;
    });
  }, []);
  const value = useMemo(() => ({ read, markRead }), [read, markRead]);
  return <ReadsContext.Provider value={value}>{children}</ReadsContext.Provider>;
}

export const useReads = () => useContext(ReadsContext);
