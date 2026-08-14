import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useVerdicts, voidVerdict, rejudgeVerdict, extendVerdictGrace } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import type { ImminentItem, JudgedItem } from "../api/types";

const RESULT_TONE: Record<string, { bg: string; fg: string }> = {
  HIT: { bg: "rgba(27,158,82,0.1)", fg: C.rising },
  MISS: { bg: "rgba(216,72,60,0.1)", fg: C.fading },
  VOID: { bg: "rgba(20,19,15,0.08)", fg: C.faint },
  "-": { bg: "rgba(20,19,15,0.08)", fg: C.faint },
};

type ActionKind = "void" | "rejudge" | "grace";

/** ADM-200. R1: 관리자는 VOID·재판정 요청·유예 연장만 할 수 있다 — 결과값을 직접 편집하는 UI는 없다. */
export default function VerdictScreen() {
  const q = useVerdicts();
  const { role } = useRole();
  const qc = useQueryClient();
  const canAct = CAN.void(role);

  const [dialog, setDialog] = useState<{ kind: ActionKind; item: JudgedItem | ImminentItem } | null>(null);
  const [reason, setReason] = useState("");
  const [days, setDays] = useState(3);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };

  const openDialog = (kind: ActionKind, item: JudgedItem | ImminentItem) => {
    setDialog({ kind, item });
    setReason("");
    setDays(3);
    setError(null);
  };

  const submit = async () => {
    if (!dialog) return;
    if (!reason.trim()) { setError("사유는 필수입니다"); return; }
    const id = dialog.item.trendItemId;
    const label = dialog.item.canonicalName;

    if (USE_FIXTURES) {
      flash(`(데모) ${label} ${actionLabel(dialog.kind)} 처리됨`);
      setDialog(null);
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      if (dialog.kind === "void") await voidVerdict(id, reason);
      else if (dialog.kind === "rejudge") await rejudgeVerdict(id, reason);
      else await extendVerdictGrace(id, days, reason);
      await qc.invalidateQueries({ queryKey: ["admin", "verdicts"] });
      flash(`${label} ${actionLabel(dialog.kind)} 처리됨`);
      setDialog(null);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "처리에 실패했습니다");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ maxWidth: 1100, display: "flex", flexDirection: "column", gap: 20 }}>
      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ padding: "14px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <b style={{ fontSize: 13.5 }}>판정 임박 (D+14 이내)</b>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 150px 150px 90px 130px", padding: "13px 20px", borderBottom: `1px solid ${C.line}` }}>
          {["항목", "최초 제보", "판정 예정", "남은 일수", ""].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(data) => (
            <>
              {data.imminent.length === 0 && (
                <div style={{ padding: "20px", color: C.faint, font: "500 12.5px Pretendard" }}>임박한 항목이 없습니다.</div>
              )}
              {data.imminent.map((it) => (
                <div key={it.trendItemId} style={{ display: "grid", gridTemplateColumns: "1fr 150px 150px 90px 130px", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                  <span style={{ font: "600 12.5px Pretendard" }}>
                    {it.canonicalName}
                    {it.graceExtended && <span style={{ marginLeft: 8, font: "600 10px ui-monospace, monospace", padding: "2px 6px", borderRadius: 5, background: "rgba(20,19,15,0.08)", color: C.faint }}>유예됨</span>}
                  </span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{it.firstSeenAt}</span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{it.deadline}</span>
                  <span style={{ font: "600 12px ui-monospace, monospace", color: it.daysLeft <= 1 ? C.fading : C.ink }}>D-{it.daysLeft}</span>
                  <Btn disabled={!canAct} title={!canAct ? "OPERATOR 이상 필요" : undefined}
                    onClick={() => openDialog("grace", it)}>유예 연장</Btn>
                </div>
              ))}
            </>
          )}
        </StateView>
      </Card>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ padding: "14px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <b style={{ fontSize: 13.5 }}>판정 완료</b>
        </div>
        <div style={{ display: "grid", gridTemplateColumns: "1fr 90px 90px 90px 150px 170px", padding: "13px 20px", borderBottom: `1px solid ${C.line}` }}>
          {["항목", "결과", "확산", "T", "판정 시각", ""].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(data) => (
            <>
              {data.judged.length === 0 && (
                <div style={{ padding: "20px", color: C.faint, font: "500 12.5px Pretendard" }}>판정 완료된 항목이 없습니다.</div>
              )}
              {data.judged.map((it) => {
                const tone = RESULT_TONE[it.result] ?? RESULT_TONE["-"];
                return (
                  <div key={it.trendItemId} style={{ display: "grid", gridTemplateColumns: "1fr 90px 90px 90px 150px 170px", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                    <span style={{ font: "600 12.5px Pretendard" }}>
                      {it.canonicalName}
                      {it.superseded && <span style={{ marginLeft: 8, font: "600 10px ui-monospace, monospace", padding: "2px 6px", borderRadius: 5, background: "rgba(20,19,15,0.08)", color: C.faint }}>재판정됨</span>}
                    </span>
                    <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: tone.bg, color: tone.fg }}>{it.result}</span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{it.reachLevel ?? "-"}</span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{it.scoreT ?? "-"}</span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{it.judgedAt ?? "-"}</span>
                    <div style={{ display: "flex", gap: 6, justifySelf: "end" }}>
                      {it.result === "VOID" ? (
                        <span style={{ font: "500 11px Pretendard", color: C.faint }}>VOID는 종결 상태입니다</span>
                      ) : (
                        <>
                          <Btn disabled={!canAct} title={!canAct ? "OPERATOR 이상 필요" : undefined}
                            onClick={() => openDialog("void", it)}>VOID</Btn>
                          <Btn disabled={!canAct} title={!canAct ? "OPERATOR 이상 필요" : undefined}
                            onClick={() => openDialog("rejudge", it)}>재판정</Btn>
                        </>
                      )}
                    </div>
                  </div>
                );
              })}
              <div style={{ padding: "14px 20px", font: "400 11.5px Pretendard", color: C.faint }}>
                R1: 판정 결과는 직접 편집할 수 없습니다. VOID·재판정은 엔진을 다시 돌려 supersede 행을 쌓는 방식으로만 처리됩니다.
              </div>
            </>
          )}
        </StateView>
      </Card>

      {dialog && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 10 }}>
          <Card style={{ width: 420 }}>
            <b style={{ fontSize: 14 }}>
              {dialog.item.canonicalName} — {actionLabel(dialog.kind)}
            </b>
            {dialog.kind === "grace" && (
              <div style={{ marginTop: 14 }}>
                <Label>연장 일수 (1~7일)</Label>
                <input type="number" min={1} max={7} value={days}
                  onChange={(e) => setDays(Number(e.target.value))} style={inp} />
              </div>
            )}
            <div style={{ marginTop: 14 }}>
              <Label>사유 (필수)</Label>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3} style={{ ...inp, resize: "vertical" }} />
            </div>
            {error && (
              <div style={{ marginTop: 12, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{error}</div>
            )}
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
              <Btn tone={dialog.kind === "void" ? "danger" : "primary"} disabled={submitting || !reason.trim()}
                onClick={submit}>{submitting ? "처리 중…" : "확인"}</Btn>
              <Btn onClick={() => setDialog(null)}>취소</Btn>
            </div>
          </Card>
        </div>
      )}

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

function actionLabel(kind: ActionKind) {
  return kind === "void" ? "VOID" : kind === "rejudge" ? "재판정 요청" : "유예 연장";
}

const inp: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" };
