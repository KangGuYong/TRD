import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useReportQueue, fetchReportSubmissionCandidates, hideReport, requestExplanation, decideReport } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import type { ReportQueueItem, ReportSubmissionCandidate } from "../api/types";

const REASON_LABEL: Record<string, string> = {
  DEFAMATION: "명예훼손", BUSINESS_INTERFERENCE: "영업방해", OTHER: "기타",
};
const STATUS_LABEL: Record<string, string> = {
  OPEN: "미처리", EXPLAINING: "소명 대기", DECIDED: "처리 완료",
};
const DECISIONS: { key: "RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE"; label: string }[] = [
  { key: "RESTORE", label: "복원" },
  { key: "HIDE_PERMANENT", label: "영구비공개" },
  { key: "EDIT_RESTORE", label: "수정 후 복원" },
];

export default function ReportQueueScreen() {
  const q = useReportQueue();
  return (
    <div style={{ maxWidth: 900 }}>
      <div style={{ marginBottom: 16, font: "500 12px Pretendard", color: C.sub }}>
        4시간 자동 임시비공개 없음 — 사람이 판단하기 전까지 절대 비공개되지 않습니다.
      </div>
      <StateView query={q}>
        {(list) => list.length === 0 ? (
          <Card><div style={{ textAlign: "center", padding: 40 }}><b>큐를 비웠습니다</b></div></Card>
        ) : (
          <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
            {list.map((r) => <ReportCard key={r.id} report={r} />)}
          </div>
        )}
      </StateView>
    </div>
  );
}

function ReportCard({ report }: { report: ReportQueueItem }) {
  const { role } = useRole();
  const qc = useQueryClient();
  const [expanded, setExpanded] = useState(false);
  const [candidates, setCandidates] = useState<ReportSubmissionCandidate[] | null>(null);
  const [loadingCandidates, setLoadingCandidates] = useState(false);
  const [selected, setSelected] = useState<string | null>(report.submissionId);
  const [note, setNote] = useState("");
  const [decision, setDecision] = useState<"RESTORE" | "HIDE_PERMANENT" | "EDIT_RESTORE">("RESTORE");
  const [newName, setNewName] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  const toggle = async () => {
    const opening = !expanded;
    setExpanded(opening);
    if (opening && !candidates) {
      setLoadingCandidates(true);
      try {
        setCandidates(USE_FIXTURES ? [] : await fetchReportSubmissionCandidates(report.id));
      } catch (e) {
        flash(e instanceof ApiError ? e.message : "제보 원문을 불러오지 못했습니다");
      } finally {
        setLoadingCandidates(false);
      }
    }
  };

  const refresh = () => qc.invalidateQueries({ queryKey: ["admin", "reports"] });

  const triage = async (action: "hide" | "request-explanation") => {
    if (!selected) { flash("소명 대상 제보를 먼저 지목하세요"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${action === "hide" ? "즉시비공개" : "소명요청"} 처리됨`); return; }
    setBusy(true);
    try {
      if (action === "hide") await hideReport(report.id, selected, note);
      else await requestExplanation(report.id, selected, note);
      await refresh();
      flash("처리됐습니다 (감사 로그 기록)");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  const decide = async () => {
    if (decision === "EDIT_RESTORE" && !newName.trim()) { flash("수정 후 복원은 새 대표명이 필요합니다"); return; }
    if (!note.trim()) { flash("결정 사유는 필수입니다"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${decision} 결정됨`); return; }
    setBusy(true);
    try {
      await decideReport(report.id, decision, note, decision === "EDIT_RESTORE" ? newName : undefined);
      await refresh();
      flash("결정이 확정됐습니다 (감사 로그 기록)");
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "결정에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Card style={{ padding: 0, overflow: "hidden" }}>
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <b style={{ fontSize: 13 }}>{REASON_LABEL[report.reason] ?? report.reason}</b>
          <span style={{ font: "600 10.5px ui-monospace, monospace", padding: "4px 8px", borderRadius: 6, background: "rgba(20,19,15,0.06)", color: C.sub }}>
            {STATUS_LABEL[report.status] ?? report.status}
          </span>
          {report.explanationDeadline && (
            <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>소명 기한 {report.explanationDeadline}</span>
          )}
        </div>
        <span style={{ font: "500 11.5px Pretendard", color: C.faint }}>접수 {report.createdAt}</span>
      </div>

      <div style={{ padding: "16px 20px" }}>
        {report.detail && <div style={{ font: "500 12.5px Pretendard", color: C.sub }}>{report.detail}</div>}

        <button onClick={toggle} style={{ marginTop: 12, background: "none", border: "none", padding: 0, cursor: "pointer", font: "600 11.5px Pretendard", color: C.sub, textDecoration: "underline" }}>
          {expanded ? "접기" : "제보 원문 보기 (소명 대상 지목)"}
        </button>

        {expanded && (
          <div style={{ marginTop: 12 }}>
            {loadingCandidates && <div style={{ color: C.faint, font: "500 12px Pretendard" }}>불러오는 중…</div>}
            {candidates?.map((c) => (
              <label key={c.submissionId} style={{ display: "flex", gap: 10, alignItems: "flex-start", padding: "10px 12px", borderRadius: 9, background: selected === c.submissionId ? "rgba(20,19,15,0.06)" : "rgba(20,19,15,0.03)", marginBottom: 8, cursor: "pointer" }}>
                <input type="radio" name={`report-${report.id}`} checked={selected === c.submissionId}
                  onChange={() => setSelected(c.submissionId)} style={{ marginTop: 3 }} disabled={report.status !== "OPEN"} />
                <div style={{ flex: 1 }}>
                  <div style={{ display: "flex", justifyContent: "space-between" }}>
                    <span style={{ font: "600 11.5px Pretendard" }}>{c.handle}</span>
                    <span style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint }}>{c.createdAt}</span>
                  </div>
                  <div style={{ font: "600 12.5px Pretendard", marginTop: 4 }}>{c.rawInput}</div>
                  <div style={{ font: "400 11.5px Pretendard", color: C.sub, marginTop: 3 }}>{c.oneLine}</div>
                  {c.evidenceUrl && (
                    <a href={c.evidenceUrl} target="_blank" rel="noreferrer" style={{ font: "500 11px Pretendard", color: C.peak, marginTop: 4, display: "inline-block" }}>근거 링크 열기 ↗</a>
                  )}
                </div>
              </label>
            ))}
          </div>
        )}

        {report.explanationText && (
          <div style={{ marginTop: 14, padding: "11px 13px", borderRadius: 10, background: "rgba(20,19,15,0.035)" }}>
            <Label>제출된 소명</Label>
            <div style={{ font: "500 12.5px Pretendard" }}>{report.explanationText}</div>
          </div>
        )}
      </div>

      {report.status === "OPEN" && (
        <div style={{ padding: "16px 20px", borderTop: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="처리 근거 (선택 · 감사 로그에 기록됩니다)"
            style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <Btn tone="danger" disabled={!CAN.reportTriage(role) || busy} onClick={() => triage("hide")}>즉시비공개 + 소명요청</Btn>
            <Btn disabled={!CAN.reportTriage(role) || busy} onClick={() => triage("request-explanation")}>공개 유지 + 소명요청</Btn>
          </div>
          {!CAN.reportTriage(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 처리 권한이 없습니다. REVIEWER 이상 필요.</div>}
        </div>
      )}

      {report.status === "EXPLAINING" && (
        <div style={{ padding: "16px 20px", borderTop: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          <Label>최종 결정</Label>
          <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
            {DECISIONS.map((d) => (
              <button key={d.key} onClick={() => setDecision(d.key)}
                style={{ padding: "8px 12px", borderRadius: 8, border: decision === d.key ? `1px solid ${C.ink}` : "1px solid rgba(20,19,15,0.14)", background: decision === d.key ? C.ink : "#fff", color: decision === d.key ? "#fff" : C.ink, cursor: "pointer", font: "600 12px Pretendard" }}>
                {d.label}
              </button>
            ))}
          </div>
          {decision === "EDIT_RESTORE" && (
            <input value={newName} onChange={(e) => setNewName(e.target.value)} placeholder="새 대표명"
              style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard", marginBottom: 10 }} />
          )}
          <input value={note} onChange={(e) => setNote(e.target.value)} placeholder="결정 사유 (필수 · 감사 로그에 기록됩니다)"
            style={{ width: "100%", boxSizing: "border-box", padding: "12px 14px", borderRadius: 10, border: "1px solid rgba(20,19,15,0.1)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
          <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
            <Btn tone="primary" disabled={!CAN.reportDecide(role) || busy} onClick={decide}>결정 확정</Btn>
          </div>
          {!CAN.reportDecide(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 결정 권한이 없습니다. OPERATOR 이상 필요.</div>}
        </div>
      )}

      {toast && (
        <div style={{ padding: "10px 20px", borderTop: `1px solid ${C.line}`, font: "500 12px Pretendard", color: C.sub }}>{toast}</div>
      )}
    </Card>
  );
}
