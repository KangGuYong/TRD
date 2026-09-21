import React, { useState } from "react";
import { USE_FIXTURES, ApiError } from "../api/client";
import { triggerClusterMerge } from "../api/hooks";
import * as fx from "../fixtures";
import type { ClusterMergeResult } from "../api/types";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

export default function BatchJobsScreen() {
  const { role } = useRole();
  const canRun = CAN.batchTrigger(role);

  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<ClusterMergeResult | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const run = async () => {
    if (!window.confirm("지금 cluster_merge를 실행하시겠습니까?")) return;

    if (USE_FIXTURES) {
      setResult(fx.fxClusterMergeResult);
      flash("(데모) cluster_merge 실행 완료");
      return;
    }

    setBusy(true);
    try {
      const r = await triggerClusterMerge();
      setResult(r);
      flash("cluster_merge 실행 완료");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "실행에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 800 }}>
      <Card>
        <b style={{ fontSize: 13 }}>cluster_merge</b>
        <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
          완전일치로 안 걸러진 항목의 임베딩 유사도를 계산해 자동병합(≥0.85)/큐적재(0.75~0.85)/별개확정(그 미만)으로 분류합니다.
          평소엔 매일 자동으로 돌지만, 여기서 즉시 실행할 수 있습니다.
        </div>

        <div style={{ marginTop: 16 }}>
          <Btn tone="primary" disabled={!canRun || busy} onClick={run}>
            {busy ? "실행 중…" : "지금 실행"}
          </Btn>
          {!canRun && (
            <span style={{ marginLeft: 10, font: "500 11.5px Pretendard", color: C.faint }}>OPERATOR 이상만 실행할 수 있습니다</span>
          )}
        </div>

        {result && (
          <div style={{ marginTop: 16, padding: 12, borderRadius: 8, background: "#f7f7f8", font: "600 12px ui-monospace, monospace" }}>
            후보 {result.candidates}건 · 자동병합 {result.autoMerged} · 큐적재 {result.queued} · 별개확정 {result.separated} · 판정된 항목과 유사 {result.skippedJudged} · 보류 {result.deferred}
            {" · "}
            <span style={{ color: result.failed > 0 ? C.fading : C.ink }}>실패 {result.failed}</span>
          </div>
        )}
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}
