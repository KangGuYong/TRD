import React from "react";
import { C, ROLE_LABEL, type Role } from "../theme";
import { useRole } from "../state/role";
import { useAuth } from "../state/auth";

export type ScreenId = "ADM-010" | "ADM-100" | "ADM-110" | "ADM-111" | "ADM-200" | "ADM-410" | "ADM-500" | "ADM-600" | "ADM-620" | "ADM-311" | "ADM-700" | "ADM-800" | "stub";

const NAV: { group: string; items: { id: ScreenId | "stub"; label: string; code: string; badge?: number }[] }[] = [
  { group: "큐", items: [
    { id: "ADM-010", label: "오늘의 작업", code: "ADM-010" },
    { id: "ADM-100", label: "병합 검수", code: "ADM-100", badge: 24 },
    { id: "stub", label: "어뷰징", code: "ADM-300", badge: 6 },
    { id: "stub", label: "이의 제기", code: "ADM-400", badge: 3 },
    { id: "ADM-410", label: "신고 콘텐츠", code: "ADM-410", badge: 2 },
  ]},
  { group: "트렌드", items: [
    { id: "ADM-110", label: "항목 목록", code: "ADM-110" },
    { id: "ADM-200", label: "판정 관리", code: "ADM-200" },
  ]},
  { group: "유저", items: [
    { id: "stub", label: "유저 목록", code: "ADM-310" },
    { id: "ADM-311", label: "유저 상세·원장", code: "ADM-311" },
  ]},
  { group: "정책", items: [
    { id: "ADM-500", label: "시딩 관리", code: "ADM-500" },
    { id: "ADM-600", label: "파라미터 스튜디오", code: "ADM-600" },
    { id: "ADM-620", label: "승인 대기함", code: "ADM-620" },
    { id: "stub", label: "등급 정책", code: "ADM-610" },
    { id: "ADM-700", label: "감사 로그", code: "ADM-700" },
    { id: "ADM-800", label: "관리자 계정", code: "ADM-800" },
  ]},
];

const ROLES: Role[] = ["REVIEWER", "OPERATOR", "ADMIN", "AUDITOR"];

export function Layout({ screen, setScreen, title, children }: {
  screen: ScreenId; setScreen: (s: ScreenId) => void; title: string; children: React.ReactNode;
}) {
  const { role, setRole, locked } = useRole();
  const { state: authState, logout } = useAuth();
  const principal = authState.status === "authenticated" ? authState.principal : null;
  return (
    <div style={{ display: "flex", height: "100vh", fontFamily: "Pretendard, system-ui, sans-serif", color: C.ink }}>
      {/* 사이드바 */}
      <aside style={{ width: 228, flex: "none", background: C.side, color: "#fff", display: "flex", flexDirection: "column", overflow: "auto" }}>
        <div style={{ padding: "20px 18px 16px" }}>
          <div style={{ font: "700 13.5px Pretendard" }}>트렌드 관리자 콘솔</div>
          <div style={{ font: "500 10.5px ui-monospace, monospace", color: "rgba(255,255,255,0.38)", marginTop: 7 }}>v0.1 · Phase 1</div>
        </div>
        <div style={{ padding: "0 12px 16px", flex: 1 }}>
          {NAV.map((g) => (
            <div key={g.group} style={{ marginBottom: 14 }}>
              <div style={{ font: "600 10px Pretendard", letterSpacing: ".11em", color: "rgba(255,255,255,0.32)", padding: "0 8px 8px" }}>{g.group.toUpperCase()}</div>
              {g.items.map((n, i) => {
                const active = n.id === screen && n.id !== "stub";
                return (
                  <button key={n.code} onClick={() => setScreen(n.id as ScreenId)}
                    style={{ width: "100%", display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8, padding: "9px", borderRadius: 8, border: "none", cursor: "pointer", textAlign: "left", background: active ? "rgba(255,255,255,0.12)" : "transparent" }}>
                    <span style={{ display: "flex", alignItems: "baseline", gap: 7, minWidth: 0 }}>
                      <span style={{ font: "500 12.5px Pretendard", color: n.id === "stub" ? "rgba(255,255,255,0.5)" : "#fff" }}>{n.label}</span>
                      <span style={{ font: "500 9px ui-monospace, monospace", color: "rgba(255,255,255,0.25)" }}>{n.code}</span>
                    </span>
                    {n.badge != null && (
                      <span style={{ font: "600 10px ui-monospace, monospace", padding: "3px 5px", borderRadius: 5, background: "rgba(255,255,255,0.12)", color: "rgba(255,255,255,0.7)" }}>{n.badge}</span>
                    )}
                  </button>
                );
              })}
            </div>
          ))}
        </div>
        {/* 역할 전환 — fixture 데모 모드에서만. 실제 로그인 시에는 서버가 준 역할이 유일한 진실. */}
        {!locked && (
          <div style={{ padding: "14px 16px 18px", borderTop: "1px solid rgba(255,255,255,0.09)" }}>
            <div style={{ font: "600 9.5px Pretendard", letterSpacing: ".11em", color: "rgba(255,255,255,0.32)", marginBottom: 9 }}>역할 전환 (권한 데모)</div>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 4 }}>
              {ROLES.map((r) => (
                <button key={r} onClick={() => setRole(r)}
                  style={{ padding: "7px 4px", borderRadius: 6, border: "none", cursor: "pointer", font: "600 9.5px ui-monospace, monospace", background: role === r ? "#fff" : "rgba(255,255,255,0.1)", color: role === r ? C.ink : "rgba(255,255,255,0.6)" }}>{r}</button>
              ))}
            </div>
            <div style={{ font: "500 11px Pretendard", color: "rgba(255,255,255,0.5)", marginTop: 10 }}>{ROLE_LABEL[role]}로 보는 중</div>
          </div>
        )}
        {locked && principal && (
          <div style={{ padding: "14px 16px 18px", borderTop: "1px solid rgba(255,255,255,0.09)" }}>
            <div style={{ font: "600 12.5px Pretendard", color: "#fff" }}>{principal.displayName}</div>
            <div style={{ font: "500 10.5px ui-monospace, monospace", color: "rgba(255,255,255,0.4)", marginTop: 3 }}>{ROLE_LABEL[principal.role]} · {principal.loginId}</div>
            <button onClick={() => logout()}
              style={{ marginTop: 10, width: "100%", padding: "8px 4px", borderRadius: 6, border: "1px solid rgba(255,255,255,0.16)", cursor: "pointer", font: "600 11px Pretendard", background: "transparent", color: "rgba(255,255,255,0.75)" }}>
              로그아웃
            </button>
          </div>
        )}
      </aside>

      {/* 본문 */}
      <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column" }}>
        <div style={{ height: 54, flex: "none", padding: "0 26px", display: "flex", alignItems: "center", justifyContent: "space-between", borderBottom: `1px solid ${C.line}`, background: "#fff" }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 10 }}>
            <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.faint }}>{screen}</span>
            <span style={{ font: "700 16px Pretendard" }}>{title}</span>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 14 }}>
            <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>모든 조회·개입은 감사 로그에 기록됩니다</span>
            <span style={{ padding: "6px 11px", borderRadius: 100, background: "rgba(20,19,15,0.06)", font: "600 11px ui-monospace, monospace" }}>{role}</span>
          </div>
        </div>
        <div style={{ flex: 1, overflow: "auto", padding: 26, background: C.bg }}>{children}</div>
      </div>
    </div>
  );
}
