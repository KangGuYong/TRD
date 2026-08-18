import React, { useState } from "react";
import { useTrendItems } from "../api/hooks";
import { Card, StateView } from "../components/ui";
import { C } from "../theme";
import type { TrendItemSummary } from "../api/types";

const STATE_TABS: { id: string; label: string; states: string[] }[] = [
  { id: "all", label: "전체", states: [] },
  { id: "active", label: "PENDING·JUDGING", states: ["PENDING", "JUDGING"] },
  { id: "resolved", label: "RESOLVED", states: ["RESOLVED"] },
  { id: "void", label: "VOID", states: ["VOID"] },
];

const RESULT_TONE: Record<string, { bg: string; fg: string }> = {
  HIT: { bg: "rgba(27,158,82,0.1)", fg: C.rising },
  MISS: { bg: "rgba(216,72,60,0.1)", fg: C.fading },
  VOID: { bg: "rgba(20,19,15,0.08)", fg: C.faint },
};

export default function TrendListScreen({ onSelect }: { onSelect: (id: string) => void }) {
  const q = useTrendItems();
  const [tab, setTab] = useState("all");
  const [category, setCategory] = useState("ALL");
  const [search, setSearch] = useState("");

  const filterRows = (rows: TrendItemSummary[]) => {
    const tabDef = STATE_TABS.find((t) => t.id === tab)!;
    return rows.filter((r) => {
      if (tabDef.states.length > 0 && !tabDef.states.includes(r.state)) return false;
      if (category !== "ALL" && r.category !== category) return false;
      if (search.trim() && !r.canonicalName.includes(search.trim())) return false;
      return true;
    });
  };

  return (
    <div style={{ maxWidth: 1100 }}>
      <div style={{ display: "flex", gap: 8, marginBottom: 14, alignItems: "center" }}>
        {STATE_TABS.map((t) => (
          <button key={t.id} onClick={() => setTab(t.id)}
            style={{ padding: "8px 14px", borderRadius: 100, border: `1px solid ${C.line}`, cursor: "pointer",
              font: "600 12px Pretendard", background: tab === t.id ? C.ink : "#fff", color: tab === t.id ? "#fff" : C.ink }}>
            {t.label}
          </button>
        ))}
        <select value={category} onChange={(e) => setCategory(e.target.value)}
          style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard" }}>
          <option value="ALL">전체 카테고리</option>
          <option value="MEME">MEME</option>
          <option value="PRODUCT">PRODUCT</option>
          <option value="PERSON_CHANNEL">PERSON_CHANNEL</option>
          <option value="CHALLENGE">CHALLENGE</option>
          <option value="SLANG">SLANG</option>
          <option value="ETC">ETC</option>
        </select>
        <input placeholder="이름 검색" value={search} onChange={(e) => setSearch(e.target.value)}
          style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", flex: 1 }} />
      </div>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: "1.6fr 110px 110px 90px 110px", padding: "13px 20px", borderBottom: `1px solid ${C.line}` }}>
          {["이름", "카테고리", "상태", "제보자수", "결과"].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => {
            const filtered = filterRows(rows);
            if (filtered.length === 0) {
              return <div style={{ padding: "20px", color: C.faint, font: "500 12.5px Pretendard" }}>조건에 맞는 항목이 없습니다.</div>;
            }
            return filtered.map((item) => {
              const tone = item.currentResult ? RESULT_TONE[item.currentResult] : null;
              return (
                <button key={item.id} onClick={() => onSelect(item.id)}
                  style={{ display: "grid", gridTemplateColumns: "1.6fr 110px 110px 90px 110px", alignItems: "center",
                    width: "100%", textAlign: "left", padding: "14px 20px", border: "none", borderBottom: `1px solid rgba(20,19,15,0.05)`,
                    background: "#fff", cursor: "pointer" }}>
                  <span style={{ font: "600 12.5px Pretendard" }}>{item.canonicalName}</span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{item.category}</span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{item.state}</span>
                  <span style={{ font: "600 12px ui-monospace, monospace" }}>{item.submitterCount}</span>
                  {tone ? (
                    <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: tone.bg, color: tone.fg }}>{item.currentResult}</span>
                  ) : (
                    <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>-</span>
                  )}
                </button>
              );
            });
          }}
        </StateView>
      </Card>
    </div>
  );
}
