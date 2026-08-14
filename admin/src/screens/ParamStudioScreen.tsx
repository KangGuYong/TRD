import React, { useEffect, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import { requestParamApproval, simulateParamDraft, updateParamDraft, useParamDraft } from "../api/hooks";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

/** ADM-600. 드래프트 → 시뮬 → 2인 승인 → 예약. 시뮬 없이는 승인요청 불가(안전장치). */
export default function ParamStudioScreen() {
  const { role } = useRole();
  const queryClient = useQueryClient();
  const { data: draft, isLoading } = useParamDraft();

  const [target, setTarget] = useState(20);
  const [hit, setHit] = useState(0.2);
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (draft) { setTarget(draft.submitterTarget); setHit(draft.hitThreshold); }
  }, [draft?.draftId, draft?.submitterTarget, draft?.hitThreshold]);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const dirty = draft != null && (target !== draft.submitterTarget || hit !== draft.hitThreshold);
  const locked = draft?.status === "REVIEW";

  const onError = (e: unknown) => flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");

  const apply = async () => {
    setBusy(true);
    try {
      const next = await updateParamDraft({ submitterTarget: target, hitThreshold: hit });
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("드래프트에 적용됨 — 시뮬레이션이 필요합니다");
    } catch (e) { onError(e); } finally { setBusy(false); }
  };

  const runSimulation = async () => {
    setBusy(true);
    try {
      const next = await simulateParamDraft();
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("시뮬레이션 완료 (과거 180일 재판정)");
    } catch (e) { onError(e); } finally { setBusy(false); }
  };

  const requestApproval = async () => {
    setBusy(true);
    try {
      const next = await requestParamApproval(reason);
      queryClient.setQueryData(["admin", "param-draft"], next);
      flash("승인 요청 생성됨 (0/2) · 기본 예약 적용");
    } catch (e) { onError(e); } finally { setBusy(false); }
  };

  if (isLoading || !draft) return <div style={{ padding: 24, font: "500 13px Pretendard", color: C.faint }}>불러오는 중...</div>;

  const canEdit = CAN.paramDraft(role) && !locked;

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderRadius: 12, background: "rgba(223,164,0,0.1)", border: "1px solid rgba(223,164,0,0.28)", marginBottom: 16 }}>
        <span style={{ font: "600 12.5px Pretendard" }}>파라미터는 즉시 반영되지 않습니다 — 드래프트 → 시뮬레이션 → 2인 승인 → 예약 적용.</span>
        <span style={{ marginLeft: "auto", font: "500 11.5px ui-monospace, monospace", color: C.faint }}>
          드래프트 #{draft.draftId.slice(0, 8)} · {draft.status === "REVIEW" ? "승인 대기중" : "작성중"}
        </span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
        <Card>
          <b style={{ fontSize: 13 }}>판정 파라미터</b>
          <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
            외부 지표 없이 제보 자체가 판정 근거입니다 — 서로 다른 후속 제보자 수만 씁니다(R1).
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 18, paddingTop: 14, borderTop: `1px solid ${C.line}` }}>
            <span style={{ font: "600 12.5px Pretendard" }}>목표 제보자 수 (T=1.0 기준)</span>
            <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{draft.currentSubmitterTarget} →</span>
              <span style={{ font: "700 13px ui-monospace, monospace", color: target !== draft.currentSubmitterTarget ? C.peak : C.ink }}>{target}</span>
              <MiniBtn disabled={!canEdit} onClick={() => setTarget((v) => Math.max(1, v - 1))}>−</MiniBtn>
              <MiniBtn disabled={!canEdit} onClick={() => setTarget((v) => v + 1)}>+</MiniBtn>
            </span>
          </div>
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 14 }}>
            <span style={{ font: "600 12.5px Pretendard" }}>판정 임계값 (HIT)</span>
            <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{draft.currentHitThreshold.toFixed(2)} →</span>
              <span style={{ font: "700 13px ui-monospace, monospace", color: hit !== draft.currentHitThreshold ? C.peak : C.ink }}>{hit.toFixed(2)}</span>
              <MiniBtn disabled={!canEdit} onClick={() => setHit((v) => Math.max(0, Math.round((v - 0.01) * 100) / 100))}>−</MiniBtn>
              <MiniBtn disabled={!canEdit} onClick={() => setHit((v) => Math.round((v + 0.01) * 100) / 100)}>+</MiniBtn>
            </span>
          </div>
          <div style={{ marginTop: 14 }}>
            <Btn disabled={!canEdit || !dirty || busy} onClick={apply}>적용</Btn>
          </div>
        </Card>

        <Card>
          <b style={{ fontSize: 13 }}>시뮬레이션 — 과거 180일 재판정</b>
          {draft.simResult ? (
            <div style={{ marginTop: 16 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                <span style={{ font: "700 32px Pretendard", letterSpacing: "-0.03em" }}>{draft.simResult.changed}</span>
                <span style={{ font: "500 12.5px Pretendard", color: C.sub }}>
                  / {draft.simResult.total}건 판정 변동 ({draft.simResult.total > 0 ? Math.round((draft.simResult.changed / draft.simResult.total) * 100) : 0}%)
                </span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 18 }}>
                <SimRow label="MISS → HIT" count={draft.simResult.missToHit} tone="up" />
                <SimRow label="HIT → MISS" count={draft.simResult.hitToMiss} tone="down" />
                <SimRow label="HIT 확산 레벨 변경" count={draft.simResult.reachChanged} tone="neutral" />
              </div>
            </div>
          ) : (
            <div style={{ padding: "26px 0", textAlign: "center", font: "500 13px Pretendard", color: C.faint, lineHeight: 1.6 }}>
              시뮬레이션을 돌리기 전에는 승인 요청을 보낼 수 없습니다.
            </div>
          )}
        </Card>
      </div>

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center" }}>
        <Btn disabled={!CAN.paramDraft(role) || locked || dirty || busy} title={dirty ? "먼저 적용하세요" : undefined} onClick={runSimulation}>시뮬레이션 실행</Btn>
        <input
          placeholder="승인 요청 사유"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          disabled={!CAN.paramDraft(role) || locked}
          style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", width: 220 }}
        />
        <Btn tone="primary" disabled={!draft.simResult || locked || !CAN.paramDraft(role) || busy} title={!draft.simResult ? "시뮬레이션 먼저" : undefined} onClick={requestApproval}>
          승인 요청 (예약·비소급)
        </Btn>
        <span style={{ marginLeft: "auto", font: "500 11.5px Pretendard", color: C.faint }}>
          적용 승인은 {CAN.paramApply(role) ? "가능(ADMIN 2인)" : "ADMIN 2인 필요"}
        </span>
      </div>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

function SimRow({ label, count, tone }: { label: string; count: number; tone: "up" | "down" | "neutral" }) {
  const col = tone === "up" ? C.rising : tone === "down" ? C.fading : C.ink;
  const bg = tone === "up" ? "rgba(27,158,82,0.09)" : tone === "down" ? "rgba(216,72,60,0.08)" : "rgba(20,19,15,0.04)";
  return (
    <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "11px 13px", borderRadius: 10, background: bg }}>
      <span style={{ font: "600 12.5px Pretendard", color: col }}>{label}</span>
      <span style={{ marginLeft: "auto", font: "700 13px ui-monospace, monospace", color: col }}>{count}건</span>
    </div>
  );
}

function MiniBtn({ children, onClick, disabled }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{ width: 24, height: 24, borderRadius: 6, border: "1px solid rgba(20,19,15,0.12)", background: "#fff", cursor: disabled ? "not-allowed" : "pointer", font: "600 13px Pretendard", color: C.ink, opacity: disabled ? 0.5 : 1 }}>{children}</button>
  );
}
