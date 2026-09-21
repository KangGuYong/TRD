import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useMergeQueue, decideMergeCandidate, fetchMergePreview } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";
import * as fx from "../fixtures";
import type { MergePreview } from "../api/types";

/** 서버 409·422 type별 안내(SP2 K2). 없는 type은 서버 detail을 그대로 보여준다. */
const DECISION_MESSAGES: Record<string, string> = {
  "merge-judging": "판정 중인 항목입니다. 잠시 후 다시 시도하세요",
  "merge-resolved": "이미 판정된 항목은 병합할 수 없습니다 (판정 후 병합은 Phase 2)",
  "merge-queue-decided": "이미 처리된 후보입니다 — 목록을 새로 고칩니다",
  "merge-target-merged": "대상 항목이 이미 다른 항목으로 병합됐습니다 — 목록을 새로 고칩니다",
  "idempotency-key-mismatch": "요청이 꼬였습니다. 새로고침 후 다시 시도하세요",
};

export default function MergeQueueScreen() {
  const q = useMergeQueue();
  const { role } = useRole();
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});

  const [previewFor, setPreviewFor] = useState<string | null>(null);
  const [previewLabel, setPreviewLabel] = useState("");
  const [previewData, setPreviewData] = useState<MergePreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };
  const toggle = (key: string) => setExpanded((e) => ({ ...e, [key]: !e[key] }));

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
      if (e instanceof ApiError && e.type && DECISION_MESSAGES[e.type]) {
        flash(DECISION_MESSAGES[e.type]);
        if (e.type === "merge-queue-decided" || e.type === "merge-target-merged") {
          await qc.invalidateQueries({ queryKey: ["admin", "merge-queue"] });
        }
      } else {
        flash(e instanceof ApiError ? e.message : `${label} 처리에 실패했습니다`);
      }
    } finally {
      setBusyId(null);
    }
  };

  const openPreview = async (id: string, label: string) => {
    setPreviewFor(id);
    setPreviewLabel(label);
    setPreviewData(null);
    setPreviewError(null);
    setReason("");
    setPreviewLoading(true);
    try {
      const data = USE_FIXTURES ? fx.fxMergePreview : await fetchMergePreview(id);
      setPreviewData(data);
    } catch (e) {
      setPreviewError(e instanceof ApiError ? e.message : "미리보기를 불러오지 못했습니다");
    } finally {
      setPreviewLoading(false);
    }
  };

  const confirmMergeFromModal = async () => {
    if (!previewFor) return;
    await act(previewFor, "병합", "merge");
    setPreviewFor(null);
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 16 }}>
        <span style={{ font: "500 12px Pretendard", color: C.sub }}>자동 병합 금지 구간 0.75–0.85</span>
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
                    <SubmissionList label={`더 보기 (제보 ${mi.newSubmissions.length}건)`}
                      open={!!expanded[`${mi.id}-new`]} onToggle={() => toggle(`${mi.id}-new`)}
                      items={mi.newSubmissions} />
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
                    <SubmissionList label={`더 보기 (제보 ${mi.oldSubmissions.length}건)`}
                      open={!!expanded[`${mi.id}-old`]} onToggle={() => toggle(`${mi.id}-old`)}
                      items={mi.oldSubmissions} />
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
                    <Btn onClick={() => openPreview(mi.id, mi.newName)}>병합 후 미리보기</Btn>
                    <Btn tone="primary" disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "병합", "merge")}>병합</Btn>
                    <Btn disabled={!CAN.merge(role) || busyId === mi.id} onClick={() => act(mi.id, "분리", "separate")}>별도 항목으로 분리</Btn>
                    <Btn tone="danger" disabled={!CAN.void(role) || busyId === mi.id} onClick={() => act(mi.id, "VOID", "void")} title={!CAN.void(role) ? "OPERATOR 이상 필요" : undefined}>VOID (허위/규정위반)</Btn>
                    <Btn disabled title="미구현 — 큐 워크플로 묶음(SP2b)에서 서버와 연결">보류 (미구현)</Btn>
                  </div>
                  {!CAN.void(role) && <div style={{ marginTop: 11, font: "500 11.5px Pretendard", color: C.fading }}>현재 역할({role})에는 VOID 권한이 없습니다. OPERATOR 이상 필요.</div>}
                </div>
              </div>
            ))}
          </Card>
        )}
      </StateView>

      {previewFor && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 10 }}>
          <Card style={{ width: 460, maxHeight: "80vh", overflow: "auto" }}>
            <b style={{ fontSize: 14 }}>{previewLabel} — 병합 후 미리보기</b>
            {previewLoading && <div style={{ marginTop: 14, color: C.faint, font: "500 12.5px Pretendard" }}>계산 중…</div>}
            {previewError && (
              <div style={{ marginTop: 14, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{previewError}</div>
            )}
            {previewData && (
              <>
                <div style={{ marginTop: 14 }}>
                  <Label>대표명</Label>
                  <div style={{ font: "600 13px Pretendard" }}>{previewData.newCanonicalName}</div>
                </div>
                <div style={{ marginTop: 14 }}>
                  <Label>선점 순위 (전 → 후)</Label>
                  <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
                    {previewData.orderRank.map((o, i) => (
                      <div key={i} style={{ display: "flex", justifyContent: "space-between", font: "500 12px ui-monospace, monospace" }}>
                        <span>{o.handle}</span>
                        <span>{o.seed ? "시딩 · 순위 없음" : `${o.rankBefore ?? "-"} → ${o.rankAfter}`}</span>
                      </div>
                    ))}
                  </div>
                </div>
                <div style={{ marginTop: 14 }}>
                  <Label>최초 목격 시각</Label>
                  <div style={{ font: "500 12.5px Pretendard" }}>{previewData.firstSeenAtBefore} → {previewData.firstSeenAtAfter}</div>
                </div>
                <div style={{ marginTop: 14 }}>
                  <Label>관측 마감</Label>
                  <div style={{ font: "500 12.5px Pretendard" }}>{previewData.deadlineBefore} → {previewData.deadlineAfter}</div>
                  {previewData.deadlineGuarded && (
                    <div style={{ marginTop: 8, padding: "9px 12px", borderRadius: 8, background: "rgba(216,150,60,0.1)", color: C.sub, font: "600 11.5px Pretendard" }}>
                      병합으로 관측 기간이 줄어 병합 시점부터 최소 3일을 보장합니다 (상한: 최초 제보 + 21일)
                    </div>
                  )}
                </div>
                {previewData.dedupVoidedHandles.length > 0 && (
                  <div style={{ marginTop: 14, padding: "9px 12px", borderRadius: 8, background: "rgba(20,19,15,0.05)", font: "500 11.5px Pretendard", color: C.sub }}>
                    동일 유저 중복 제보로 VOID 처리될 제보자: {previewData.dedupVoidedHandles.join(", ")}
                  </div>
                )}
                {previewData.quotaRefundHandles.length > 0 && (
                  <div style={{ marginTop: 8, padding: "9px 12px", borderRadius: 8, background: "rgba(20,19,15,0.05)", font: "500 11.5px Pretendard", color: C.sub }}>
                    제보권 1장이 반환될 제보자: {previewData.quotaRefundHandles.join(", ")}
                  </div>
                )}
                <div style={{ marginTop: 16 }}>
                  <Label>사유</Label>
                  <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="판단 근거 (선택 · 감사 로그에 기록됩니다)"
                    style={{ width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" }} />
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
                  <Btn tone="primary" disabled={!CAN.merge(role) || busyId === previewFor} onClick={confirmMergeFromModal}>이 내용으로 병합</Btn>
                  <Btn onClick={() => setPreviewFor(null)}>닫기</Btn>
                </div>
              </>
            )}
          </Card>
        </div>
      )}

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

function SubmissionList({ label, open, onToggle, items }: {
  label: string; open: boolean; onToggle: () => void;
  items: { handle: string; rawInput: string; oneLine: string; evidenceUrl: string; createdAt: string }[];
}) {
  return (
    <div style={{ marginTop: 12 }}>
      <button onClick={onToggle} style={{ background: "none", border: "none", padding: 0, cursor: "pointer", font: "600 11.5px Pretendard", color: C.sub, textDecoration: "underline" }}>
        {open ? "접기" : label}
      </button>
      {open && (
        <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 10 }}>
          {items.map((s, i) => (
            <div key={i} style={{ padding: "10px 12px", borderRadius: 9, background: "rgba(20,19,15,0.03)" }}>
              <div style={{ display: "flex", justifyContent: "space-between" }}>
                <span style={{ font: "600 11.5px Pretendard" }}>{s.handle}</span>
                <span style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint }}>{s.createdAt}</span>
              </div>
              <div style={{ font: "600 12.5px Pretendard", marginTop: 4 }}>{s.rawInput}</div>
              <div style={{ font: "400 11.5px Pretendard", color: C.sub, marginTop: 3 }}>{s.oneLine}</div>
              {s.evidenceUrl && (
                <a href={s.evidenceUrl} target="_blank" rel="noreferrer"
                  style={{ font: "500 11px Pretendard", color: C.peak, marginTop: 4, display: "inline-block" }}>
                  근거 링크 열기 ↗
                </a>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
