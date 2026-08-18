import React from "react";
import { useQueueSummary } from "../api/hooks";
import { Card, StateView } from "../components/ui";
import { C } from "../theme";

export default function TodayScreen({ goMerge }: { goMerge: () => void }) {
  const q = useQueueSummary();
  return (
    <div style={{ maxWidth: 1000 }}>
      <StateView query={q}>
        {(d) => (
          <>
            {d.slaBreaches > 0 && (
              <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "14px 18px", borderRadius: 12, background: "rgba(216,72,60,0.08)", border: "1px solid rgba(216,72,60,0.2)", marginBottom: 20 }}>
                <span style={{ width: 8, height: 8, borderRadius: 9, background: C.fading }} />
                <b style={{ color: C.fading, fontSize: 13.5 }}>SLA 초과 {d.slaBreaches}건</b>
                <span style={{ color: C.sub, fontSize: 12.5 }}>
                  {d.alerts.length > 0 ? d.alerts.map((a) => a.title).join(" · ") : "SLA 기준을 넘긴 큐가 있습니다"}
                </span>
              </div>
            )}

            <div style={{ display: "grid", gridTemplateColumns: "repeat(4,1fr)", gap: 12 }}>
              {d.queues.map((qc) => (
                <button key={qc.id} onClick={qc.id === "ADM-100" ? goMerge : undefined}
                  style={{ textAlign: "left", background: "#fff", borderRadius: 14, padding: 18, cursor: qc.id === "ADM-100" ? "pointer" : "default", border: `1px solid ${qc.slaExceeded ? "rgba(216,72,60,0.35)" : C.line}` }}>
                  <div style={{ display: "flex", justifyContent: "space-between" }}>
                    <span style={{ font: "600 12.5px Pretendard", color: C.sub }}>{qc.name}</span>
                    <span style={{ font: "500 9.5px ui-monospace, monospace", color: C.faint }}>{qc.id}</span>
                  </div>
                  <div style={{ font: "700 34px Pretendard", letterSpacing: "-0.03em", marginTop: 14, color: qc.slaExceeded ? C.fading : C.ink }}>{qc.count}</div>
                  <div style={{ font: "500 11.5px Pretendard", marginTop: 9, color: qc.slaExceeded ? C.fading : C.sub }}>최장 대기 {qc.oldest}{qc.slaExceeded ? " ⚠" : ""}</div>
                </button>
              ))}
            </div>

            <div style={{ display: "grid", gridTemplateColumns: "1.35fr 1fr", gap: 12, marginTop: 12 }}>
              <Card>
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 14 }}>
                  <span style={{ width: 7, height: 7, borderRadius: 9, background: C.fading }} />
                  <b style={{ fontSize: 13 }}>시스템 알림</b>
                </div>
                <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                  {d.alerts.map((a, i) => (
                    <div key={i} style={{ display: "flex", alignItems: "center", gap: 12, padding: "13px 14px", borderRadius: 11, background: "rgba(20,19,15,0.035)" }}>
                      <span style={{ width: 6, height: 6, borderRadius: 9, background: C.fading, flex: "none" }} />
                      <span>
                        <span style={{ display: "block", font: "600 12.5px Pretendard" }}>{a.title}</span>
                        <span style={{ display: "block", font: "400 11.5px Pretendard", color: C.sub, marginTop: 3 }}>{a.detail}</span>
                      </span>
                    </div>
                  ))}
                </div>
              </Card>

              <Card>
                <b style={{ fontSize: 13 }}>오늘의 판정</b>
                <div style={{ display: "flex", gap: 26, marginTop: 16 }}>
                  <div><div style={{ font: "400 11.5px Pretendard", color: C.faint }}>판정 예정</div><div style={{ font: "700 30px Pretendard", marginTop: 8 }}>{d.judgedToday}</div></div>
                  <div><div style={{ font: "400 11.5px Pretendard", color: C.faint }}>D+14 임박 24h</div><div style={{ font: "700 30px Pretendard", marginTop: 8 }}>{d.imminent24h}</div></div>
                </div>
                <div style={{ height: 1, background: C.line, margin: "18px 0 16px" }} />
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: 9 }}>
                  <span style={{ font: "500 11.5px Pretendard", color: C.sub }}>시딩 비중</span>
                  <span style={{ font: "600 12px ui-monospace, monospace" }}>{Math.round(d.seedRatio * 100)}%</span>
                </div>
                <div style={{ height: 6, borderRadius: 9, background: "rgba(20,19,15,0.08)", overflow: "hidden" }}>
                  <div style={{ height: "100%", width: `${d.seedRatio * 100}%`, borderRadius: 9, background: C.peak }} />
                </div>
                <div style={{ font: "400 11px Pretendard", color: C.faint, marginTop: 9 }}>유저 제보 비중 {Math.round((1 - d.seedRatio) * 100)}% · Phase 2 전환 기준 70%</div>
              </Card>
            </div>
          </>
        )}
      </StateView>
    </div>
  );
}
