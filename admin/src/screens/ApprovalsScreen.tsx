import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useApprovals, approveApproval, rejectApproval } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import { useAuth } from "../state/auth";
import type { ApprovalRequestView } from "../api/types";

// 좁은 화면에서는 요약 열을 짓누르지 않고 가로 스크롤한다(TrendDetailScreen 제보 이력과 같은 방식).
const ROW_GRID: React.CSSProperties = { display: "grid", gridTemplateColumns: "140px 90px 110px 1fr 90px 130px 170px", minWidth: 1000 };

const ACTION_LABEL: Record<ApprovalRequestView["actionType"], string> = {
  PARAM_APPLY: "파라미터 적용",
  VERDICT_REJUDGE: "재판정(100+)",
  ITEM_VOID: "판정 VOID(100+)",
  LEDGER_ADJ: "원장 조정",
  ACCOUNT_CREATE: "계정 생성",
  ACCOUNT_ROLE_CHANGE: "역할 변경",
};

const STATUS_LABEL: Record<ApprovalRequestView["status"], string> = {
  PENDING: "대기",
  APPROVED: "승인됨",
  REJECTED: "반려됨",
  EXECUTED: "실행됨",
};

/** ADM-620 승인 대기함. ADM-320/600/610 등 여러 화면이 공통으로 만드는 approval_requests를 한 곳에서 처리. */
export default function ApprovalsScreen() {
  const q = useApprovals();
  const { role } = useRole();
  const { state: authState } = useAuth();
  const principal = authState.status === "authenticated" ? authState.principal : null;
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };
  const canConfirm = CAN.approvalConfirm(role);

  const approve = async (row: ApprovalRequestView) => {
    if (USE_FIXTURES) { flash(`(데모) ${row.id} 승인 처리됨`); return; }
    setBusyId(row.id);
    try {
      await approveApproval(row.id);
      await qc.invalidateQueries({ queryKey: ["admin", "approvals"] });
      flash(`${ACTION_LABEL[row.actionType]} 승인 처리됨`);
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "승인에 실패했습니다");
    } finally {
      setBusyId(null);
    }
  };

  const reject = async (row: ApprovalRequestView) => {
    if (!reason.trim()) { flash("반려 사유를 입력하세요"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${row.id} 반려됨 — ${reason}`); return; }
    setBusyId(row.id);
    try {
      await rejectApproval(row.id, reason);
      await qc.invalidateQueries({ queryKey: ["admin", "approvals"] });
      flash(`${ACTION_LABEL[row.actionType]} 반려됨`);
      setReason("");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "반려에 실패했습니다");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div style={{ maxWidth: 1100 }}>
      <div style={{ marginBottom: 12, display: "flex", gap: 8, alignItems: "center" }}>
        <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="반려 사유 (반려 시 필수 · 감사 로그에 기록됩니다)"
          style={{ flex: 1, padding: "10px 13px", borderRadius: 9, border: `1px solid ${C.line}`, font: "500 12.5px Pretendard" }} />
      </div>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ overflowX: "auto" }}>
        <div style={{ ...ROW_GRID, padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["액션", "대상", "요청자", "요약", "승인현황", "생성시각", ""].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => (
            <>
              {rows.length === 0 && (
                <div style={{ padding: "26px 20px", font: "500 13px Pretendard", color: C.faint }}>대기 중인 승인 요청이 없습니다.</div>
              )}
              {rows.map((row) => {
                const isRequester = principal != null && row.requestedBy === principal.id;
                const disabled = !canConfirm || isRequester || busyId === row.id;
                return (
                  <div key={row.id} style={{ ...ROW_GRID, alignItems: "center", padding: "14px 20px", borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: "rgba(20,19,15,0.06)", color: C.ink }}>
                      {ACTION_LABEL[row.actionType]}
                    </span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{row.targetRef.slice(0, 8)}</span>
                    <span style={{ font: "600 12.5px Pretendard" }}>{row.requestedByName}</span>
                    <span style={{ font: "500 12px Pretendard", color: C.sub }}>{row.summary}</span>
                    <span style={{ font: "700 12px ui-monospace, monospace", color: row.approvals > 0 ? C.peak : C.faint }} title={STATUS_LABEL[row.status]}>
                      {row.approvals}/{row.requiredApprovals}
                    </span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{row.createdAt}</span>
                    <div style={{ display: "flex", gap: 6, justifySelf: "end" }}>
                      <Btn tone="primary" disabled={disabled} title={isRequester ? "요청자 본인은 승인할 수 없습니다" : !canConfirm ? "ADMIN 필요" : undefined}
                        onClick={() => approve(row)}>승인</Btn>
                      <Btn tone="danger" disabled={disabled} title={isRequester ? "요청자 본인은 반려할 수 없습니다" : !canConfirm ? "ADMIN 필요" : undefined}
                        onClick={() => reject(row)}>반려</Btn>
                    </div>
                  </div>
                );
              })}
            </>
          )}
        </StateView>
        </div>
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}
