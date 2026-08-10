import React from "react";
import { C } from "../theme";

export function Card({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return (
    <div style={{ background: C.card, border: `1px solid ${C.line}`, borderRadius: 14, padding: 20, ...style }}>
      {children}
    </div>
  );
}

export function StateView<T>({ query, children }: { query: { isLoading: boolean; isError: boolean; error?: unknown; data?: T }; children: (d: T) => React.ReactNode }) {
  if (query.isLoading) return <div style={{ padding: 40, color: C.faint }}>불러오는 중…</div>;
  if (query.isError || query.data === undefined) {
    const msg = query.error instanceof Error ? query.error.message : "불러오지 못했어요";
    return (
      <div style={{ padding: 24, borderRadius: 12, background: "rgba(216,72,60,0.07)", border: "1px solid rgba(216,72,60,0.2)", color: C.ink }}>
        <b style={{ color: C.fading }}>연결 실패</b> · {msg}
        <div style={{ color: C.sub, fontSize: 12.5, marginTop: 6 }}>백엔드 /admin 엔드포인트 미구현 시 발생. VITE_USE_FIXTURES=true 로 시연 가능.</div>
      </div>
    );
  }
  return <>{children(query.data)}</>;
}

export function Btn({ children, onClick, disabled, tone = "default", title }: {
  children: React.ReactNode; onClick?: () => void; disabled?: boolean; tone?: "default" | "primary" | "danger"; title?: string;
}) {
  const bg = disabled ? "rgba(20,19,15,0.1)" : tone === "primary" ? C.ink : tone === "danger" ? C.fading : "#fff";
  const fg = disabled ? "rgba(20,19,15,0.35)" : tone === "default" ? C.ink : "#fff";
  const border = tone === "default" && !disabled ? "1px solid rgba(20,19,15,0.14)" : "none";
  return (
    <button onClick={onClick} disabled={disabled} title={title}
      style={{ padding: "10px 16px", borderRadius: 10, border, background: bg, color: fg, cursor: disabled ? "not-allowed" : "pointer", font: "600 12.5px Pretendard, system-ui, sans-serif" }}>
      {children}
    </button>
  );
}

export function Label({ children }: { children: React.ReactNode }) {
  return <div style={{ font: "600 12px Pretendard, system-ui", color: C.faint, marginBottom: 8 }}>{children}</div>;
}
