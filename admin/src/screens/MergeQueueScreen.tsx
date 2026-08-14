import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useMergeQueue, decideMergeCandidate } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

export default function MergeQueueScreen() {
  const q = useMergeQueue();
  const { role } = useRole();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const act = async (id: string, label: string, action: "merge" | "separate" | "void") => {
    if (USE_FIXTURES) {
      flash(`(데모) ${label} 처리됨. 근거: ${reason || "—"}`);
      setReason("");
      return;
    }
    setBusyId(id);
    try {
      await decideMergeCandidate(id, action, reason);
      await qc.invalidateQueries({ queryKey: ["admin", "merge-queue"] });
      flash(`${label} 처리됨 (감사 로그 기록)`);
      setReason("");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : `${label} 처리에 실패했습니다`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <span style={{ font: "500 12px Pretendard", color: C.sub }}>자동 병합 금지 구간 0.75–0.85</span>
        <span style={{ display: "flex", gap: 5 }}>
          {[["J/K", "이동"], ["M", "병합"], ["S", "분리"], ["V", "VOID"]].map(([k, d]) => (
            <span key={k} style={{ font: "600 10px ui-monospace, monospace", padding: "5px 6px", borderRadius: 5, background: "rgba(20,19,15,0.06)", color: C.sub }}>{k} {d}</span>
          ))}
        </span>
      </div>

      <StateView query={q}>
        {(list) => list.length === 0 ? (
          <Card><div style={{ textAlign: "center", padding: 40 }}><b>큐를 비웠습니다</b></div></Card>
        ) : (
          <Card style={{ padding: 0, overflow: "hidden" }}>
            {list.map((mi) => (
              <div key={mi.id}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                    <b style={{ fontSize: 13 }}>유사도 {mi.similarity}</b>
                    <span style={{ height: 5, width: 120, borderRadius: 9, background: "rgba(20,19,15,0.09)", overflow: "hidden", display: "block" }}>
                      <span style={{ display: "block", height: "100%", borderRadius: 9, background: C.peak, width: `${mi.similarity * 100}%` }} />
                    </span>
                  </div>
                  <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>제보 {mi.ago}</span>
                </div>

                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr" }}>
                  <div style={{ padding: 20, borderRight: `1px solid ${C.line}` }}>
                    <div style={{ font: "600 10.5px Pretendard", letterSpacing: ".09em", color: C.faint, marginBottom: 12 }}>신규 제보</div>
                    <div style={{ font: "700 22px Pretendard", letterSpacing: "-0.02em" }}>{mi.newName}</div>
                    <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 16 }}>
                      {mi.newRows.map((r, i) => <Row key={i} k={r.k} v={r.v} />)}
                    </div>
                    <div style={{ marginTop: 14, padding: "11px 13px", borderRadius: 10, background: "rgba(20,19,15,0.035)", font: "400 11px Pretendard", color: C.sub, lineHeight: 1.5 }}>
                      제보자 등급은 참고 정보입니다. 고등급자 제보를 우대하면 공정성이 깨집니다 — 병합 판단에 반영하지 마세요.
                    </div>
                  </div>
                  <div style={{ padding: 20, background: "rgba(20,19,15,0.02)" }}>
                    <div style={{ font: "600 10.5px Pretendard", letterSpacing: ".09em", color: C.faint, marginBottom: 12 }}>기존 클러스터</div>
                    <div style={{ display: "flex", alignItems: "baseline", gap: 9 }}>
                      <span style={{ font: "700 22px Pretendard", letterSpacing: "-0.02em" }}>{mi.oldName}</span>
                      <span style={{ font: "600 12px ui-monospace, monospace", color: C.faint }}>{mi.oldClusterId}</span>
                    </div>
                    <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 16 }}>
                      {mi.oldRows.map((r, i) => <Row key={i} k={r.k} v={r.v} />)}
                    </div>
                    <div style={{ marginTop: 14, padding: "12px 13px", borderRadius: 10, background: "#fff", border: "1px dashed rgba(20,19,15,0.16)" }}>
                      <div style={{ font: "600 11px Pretendard", color: C.sub, marginBottom: 8 }}>병합 시 선점 순위 미리보기</div>
                      <div style={{ display: "flex", flexWrap: "wrap", gap: 5 }}>
                        {mi.orderPreview.map((o, i) => (
                          <span key={i} style={{ font: "600 10.5px ui-monospace, monospace", padding: "5px 7px", borderRadius: 6, background: "rgba(20,19,15,0.06)", color: C.ink }}>{o}</span>
                        ))}
                      </div>
                    </div>
                  </div>
                </div>

                <div style={{ padding: "18px 20px", borderTop: `1px solid ${C.line}` }}>
                  <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="판단 근거 (선택 · 감사 로그에 기록됩니다)"
                    style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
                  <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                    <Btn tone="primary" disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "병합", "merge")}>병합</Btn>
                    <Btn disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "분리", "separate")}>별도 항목으로 분리</Btn>
                    <Btn tone="danger" disabled={!CAN.void(role) || busyId === mi.id} onClick={() => act(mi.id, "VOID", "void")} title={!CAN.void(role) ? "OPERATOR 이상 필요" : undefined}>VOID (허위/규정위반)</Btn>
                    <Btn onClick={() => flash("다음 항목으로 보류")}>보류 → 다음</Btn>
                  </div>
                  {!CAN.void(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 VOID 권한이 없습니다. OPERATOR 이상 필요.</div>}
                </div>
              </div>
            ))}
          </Card>
        )}
      </StateView>

      {toast && (
        <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard", boxShadow: "0 8px 24px rgba(0,0,0,0.2)" }}>{toast}</div>
      )}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div style={{ display: "flex", gap: 14 }}>
      <span style={{ width: 78, flex: "none", font: "500 11.5px Pretendard", color: C.faint }}>{k}</span>
      <span style={{ font: "500 12.5px Pretendard" }}>{v}</span>
    </div>
  );
}
