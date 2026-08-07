import React from "react";

/**
 * 콘솔 셸. 큐 중심(대시보드 아님) · 데스크톱 전용(02 P1, §4).
 * 진입 = ADM-010 오늘의 작업. 네비 그룹은 관리자 콘솔.dc.html 구조를 따른다.
 * 권한 가드는 서버 응답 기반(클라 렌더 가드는 UX용) — 실제 통제는 api-admin의 RBAC.
 */
const NAV = [
  { group: "큐", items: [["ADM-010", "오늘의 작업"], ["ADM-100", "병합 검수"], ["ADM-300", "어뷰징"], ["ADM-400", "이의 제기"], ["ADM-410", "신고 콘텐츠"]] },
  { group: "트렌드", items: [["ADM-110", "항목 목록"], ["ADM-200", "판정 관리"], ["ADM-210", "수집 모니터"]] },
  { group: "유저", items: [["ADM-310", "유저 목록"], ["ADM-320", "제재 관리"]] },
  { group: "정책", items: [["ADM-600", "파라미터 스튜디오"], ["ADM-610", "등급 정책"], ["ADM-700", "감사 로그"], ["ADM-800", "관리자 계정"]] },
];

export default function App() {
  return (
    <div style={{ display: "flex", height: "100vh", fontFamily: "Pretendard, system-ui, sans-serif", color: "#14130F" }}>
      <aside style={{ width: 228, background: "#14130F", color: "#fff", padding: 16, overflow: "auto" }}>
        <div style={{ fontWeight: 700, fontSize: 13.5 }}>트렌드 관리자 콘솔</div>
        <div style={{ fontSize: 10.5, opacity: 0.4, marginTop: 6, fontFamily: "monospace" }}>v0.1 · Phase 1</div>
        {NAV.map((g) => (
          <div key={g.group} style={{ marginTop: 18 }}>
            <div style={{ fontSize: 10, letterSpacing: ".11em", opacity: 0.35, marginBottom: 8 }}>{g.group.toUpperCase()}</div>
            {g.items.map(([id, label]) => (
              <div key={id} style={{ display: "flex", justifyContent: "space-between", padding: "8px 8px", borderRadius: 8, fontSize: 12.5 }}>
                <span>{label}</span>
                <span style={{ fontSize: 9, opacity: 0.3, fontFamily: "monospace" }}>{id}</span>
              </div>
            ))}
          </div>
        ))}
      </aside>
      <main style={{ flex: 1, background: "#F4F2ED", padding: 26 }}>
        <h1 style={{ fontSize: 20 }}>오늘의 작업 · ADM-010</h1>
        <p style={{ color: "rgba(20,19,15,0.55)", fontSize: 13 }}>
          화면 구현은 05-screen-endpoint-map.md §B / §E 순서(010 → 100 → 600 → 311 → 700)로 채운다.
          모든 조회·개입은 감사 로그에 기록된다.
        </p>
      </main>
    </div>
  );
}
