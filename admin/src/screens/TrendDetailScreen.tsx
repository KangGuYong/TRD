import React, { useEffect, useState } from "react";
import { fetchTrendItemDetail, voidVerdict, extendVerdictGrace } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import * as fx from "../fixtures";
import type { TrendItemDetail } from "../api/types";

type ActionKind = "void" | "grace";

export default function TrendDetailScreen({ trendItemId, onBack }: { trendItemId: string | null; onBack: () => void }) {
  const { role } = useRole();
  const canAct = CAN.void(role);

  const [detail, setDetail] = useState<TrendItemDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [dialog, setDialog] = useState<ActionKind | null>(null);
  const [reason, setReason] = useState("");
  const [days, setDays] = useState(3);
  const [submitting, setSubmitting] = useState(false);
  const [dialogError, setDialogError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };

  const load = async (id: string) => {
    setLoading(true);
    setLoadError(null);
    try {
      const data = USE_FIXTURES ? fx.fxTrendItemDetail : await fetchTrendItemDetail(id);
      setDetail(data);
    } catch (e) {
      setLoadError(e instanceof ApiError ? e.message : "불러오기에 실패했습니다");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (trendItemId) load(trendItemId);
  }, [trendItemId]);

  const openDialog = (kind: ActionKind) => {
    setDialog(kind);
    setReason("");
    setDays(3);
    setDialogError(null);
  };

  const submit = async () => {
    if (!dialog || !detail) return;
    if (!reason.trim()) { setDialogError("사유는 필수입니다"); return; }

    if (USE_FIXTURES) {
      flash(`(데모) ${detail.canonicalName} ${dialog === "void" ? "VOID" : "유예 연장"} 처리됨`);
      setDialog(null);
      return;
    }
    setSubmitting(true);
    setDialogError(null);
    try {
      if (dialog === "void") await voidVerdict(detail.id, reason);
      else await extendVerdictGrace(detail.id, days, reason);
      flash(`${detail.canonicalName} ${dialog === "void" ? "VOID" : "유예 연장"} 처리됨`);
      setDialog(null);
      await load(detail.id);
    } catch (e) {
      setDialogError(e instanceof ApiError ? e.message : "처리에 실패했습니다");
    } finally {
      setSubmitting(false);
    }
  };

  if (!trendItemId) {
    return (
      <div style={{ maxWidth: 700, padding: 40, textAlign: "center" }}>
        <div style={{ font: "500 13px Pretendard", color: C.faint, marginBottom: 14 }}>목록에서 항목을 선택하세요.</div>
        <Btn onClick={onBack}>목록으로</Btn>
      </div>
    );
  }

  if (loading || !detail) {
    return <div style={{ padding: 40, color: C.faint, font: "500 13px Pretendard" }}>불러오는 중…</div>;
  }

  if (loadError) {
    return (
      <div style={{ maxWidth: 700, padding: 40, textAlign: "center" }}>
        <div style={{ font: "600 13px Pretendard", color: C.fading, marginBottom: 14 }}>{loadError}</div>
        <Btn onClick={onBack}>목록으로</Btn>
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 900 }}>
      <button onClick={onBack} style={{ background: "none", border: "none", cursor: "pointer", font: "600 12px Pretendard", color: C.faint, marginBottom: 12, padding: 0 }}>
        ← 목록으로
      </button>

      <Card>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <b style={{ fontSize: 16 }}>{detail.canonicalName}</b>
          <span style={{ font: "600 10.5px ui-monospace, monospace", padding: "3px 7px", borderRadius: 5, background: "rgba(20,19,15,0.08)", color: C.faint }}>{detail.state}</span>
          {detail.state !== "RESOLVED" && detail.state !== "VOID" && (
            <span style={{ font: "600 11px ui-monospace, monospace", color: detail.daysLeft <= 1 ? C.fading : C.faint }}>D-{detail.daysLeft}</span>
          )}
        </div>
        <div style={{ display: "flex", gap: 26, marginTop: 16 }}>
          <Stat label="서로 다른 제보자" value={String(detail.distinctSubmitters)} />
          <Stat label="서로 다른 플랫폼" value={String(detail.distinctPlatforms)} />
          <Stat label="공감(endorse)" value={String(detail.endorseCount)} />
        </div>

        {detail.previewResult ? (
          <div style={{ marginTop: 18, padding: "14px 16px", borderRadius: 10, background: "rgba(223,164,0,0.08)", border: "1px solid rgba(223,164,0,0.24)" }}>
            <span style={{ font: "600 12.5px Pretendard" }}>현재 T = {detail.previewScoreT} → 예상 판정: {detail.previewResult}{detail.previewReachLevel ? ` / ${detail.previewReachLevel}` : ""}</span>
            <div style={{ font: "500 11px Pretendard", color: C.faint, marginTop: 6 }}>※ 확정 아님. D+14 시점 재계산됩니다.</div>
          </div>
        ) : detail.currentResult ? (
          <div style={{ marginTop: 18, padding: "14px 16px", borderRadius: 10, background: "rgba(20,19,15,0.03)" }}>
            <span style={{ font: "600 12.5px Pretendard" }}>확정 판정: {detail.currentResult}{detail.currentReachLevel ? ` / ${detail.currentReachLevel}` : ""} (T={detail.currentScoreT})</span>
            <div style={{ font: "500 11px Pretendard", color: C.faint, marginTop: 6 }}>판정 시각: {detail.currentJudgedAt}</div>
          </div>
        ) : null}
      </Card>

      <Card style={{ marginTop: 12, padding: 0, overflow: "hidden" }}>
        <div style={{ padding: "14px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <b style={{ fontSize: 13.5 }}>제보 이력</b>
        </div>
        <div style={{ overflowX: "auto" }}>
        <div style={{ display: "grid", gridTemplateColumns: "50px 110px 80px 1fr 130px 70px 60px 140px", minWidth: 900, padding: "13px 20px", borderBottom: `1px solid ${C.line}` }}>
          {["순위", "제보자", "플랫폼", "제보 근거(한 줄)", "근거 URL", "확신도", "TI", "시각"].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        {detail.submissions.map((s) => (
          <div key={s.submissionId} style={{ display: "grid", gridTemplateColumns: "50px 110px 80px 1fr 130px 70px 60px 140px", minWidth: 900, alignItems: "center", padding: "12px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
            <span style={{ font: "600 12px ui-monospace, monospace" }}>{s.orderRank}</span>
            <span style={{ font: "600 12.5px Pretendard" }}>{s.userHandle}</span>
            <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>{s.platform}</span>
            <span style={{ font: "500 12px Pretendard", paddingRight: 10 }}>{s.oneLine}</span>
            <a href={s.evidenceUrl} target="_blank" rel="noreferrer"
              style={{ font: "500 11px ui-monospace, monospace", color: C.peak, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
              {s.evidenceUrl}
            </a>
            <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>c={s.confidence}</span>
            <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{s.submitterTi != null ? s.submitterTi.toFixed(2) : "-"}</span>
            <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{s.createdAt}</span>
          </div>
        ))}
        </div>
      </Card>

      <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
        <Btn tone="danger" disabled={!canAct} title={!canAct ? "OPERATOR 이상 필요" : undefined} onClick={() => openDialog("void")}>VOID 처리</Btn>
        <Btn disabled={!canAct} title={!canAct ? "OPERATOR 이상 필요" : undefined} onClick={() => openDialog("grace")}>판정 유예 연장</Btn>
      </div>

      {dialog && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 10 }}>
          <Card style={{ width: 420 }}>
            <b style={{ fontSize: 14 }}>{detail.canonicalName} — {dialog === "void" ? "VOID 처리" : "판정 유예 연장"}</b>
            {dialog === "grace" && (
              <div style={{ marginTop: 14 }}>
                <Label>연장 일수 (1~7일)</Label>
                <input type="number" min={1} max={7} value={days}
                  onChange={(e) => setDays(Number(e.target.value))}
                  style={{ width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
              </div>
            )}
            <div style={{ marginTop: 14 }}>
              <Label>사유 (필수)</Label>
              <textarea value={reason} onChange={(e) => setReason(e.target.value)} rows={3}
                style={{ width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard", resize: "vertical" }} />
            </div>
            {dialogError && (
              <div style={{ marginTop: 12, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{dialogError}</div>
            )}
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
              <Btn tone={dialog === "void" ? "danger" : "primary"} disabled={submitting || !reason.trim()} onClick={submit}>
                {submitting ? "처리 중…" : "확인"}
              </Btn>
              <Btn onClick={() => setDialog(null)}>취소</Btn>
            </div>
          </Card>
        </div>
      )}

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div style={{ font: "400 11.5px Pretendard", color: C.faint }}>{label}</div>
      <div style={{ font: "700 22px Pretendard", marginTop: 6 }}>{value}</div>
    </div>
  );
}
