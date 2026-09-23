import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAdminUser, useAdminUserSearch, adjustLedger } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

const KIND_COLOR: Record<string, string> = { HIT: C.rising, MISS: C.fading, VOID: C.faint, ADJ: C.peak };

/** ADM-311. 원장 수정·삭제 UI는 없다 — 정정은 ADJ 추가뿐(R2). 100점 초과·OPERATOR 요청은 2인 승인. */
export default function UserLedgerScreen() {
  const { role } = useRole();
  const qc = useQueryClient();
  const [handle, setHandle] = useState("");
  const [userId, setUserId] = useState<string | null>(null);
  const hits = useAdminUserSearch(handle);
  const q = useAdminUser(userId);
  const [adjOpen, setAdjOpen] = useState(false);
  const [amt, setAmt] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const amount = parseFloat(amt);
  const needsApproval = role === "OPERATOR" || Math.abs(amount || 0) > 100;
  const adjReady = !!reason.trim() && !isNaN(amount) && amount !== 0;
  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };

  const submitAdj = async () => {
    if (!userId || !adjReady) return;
    if (USE_FIXTURES) { flash("(데모) ADJ 요청"); return; }
    setBusy(true);
    try {
      const r = await adjustLedger(userId, amount, reason.trim());
      flash(r.status === "APPLIED" ? `ADJ ${amount} 기록됨` : "승인 대기로 올렸습니다 — 다른 ADMIN의 승인 후 반영됩니다");
      setAdjOpen(false); setAmt(""); setReason("");
      await qc.invalidateQueries({ queryKey: ["admin", "user", userId] });
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "ADJ 요청에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      {!USE_FIXTURES && (
        <Card style={{ marginBottom: 12 }}>
          <input value={handle} onChange={(e) => setHandle(e.target.value)} placeholder="유저 핸들 앞부분 (2자 이상)" style={inp} />
          {hits.data && hits.data.length > 0 && (
            <div style={{ marginTop: 10, display: "flex", flexWrap: "wrap", gap: 6 }}>
              {hits.data.map((h) => (
                <Btn key={h.id} tone={h.id === userId ? "primary" : undefined} onClick={() => setUserId(h.id)}>
                  {h.handle} · {h.grade}
                </Btn>
              ))}
            </div>
          )}
          {hits.data && hits.data.length === 0 && <div style={{ marginTop: 10, font: "500 12px Pretendard", color: C.faint }}>일치하는 유저가 없습니다</div>}
        </Card>
      )}

      {(USE_FIXTURES || userId) && (
        <StateView query={q}>
          {(u) => (
            <>
              <Card>
                <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                  <span style={{ font: "700 21px ui-monospace, monospace", letterSpacing: "-0.02em" }}>{u.handle}</span>
                  <span style={{ padding: "5px 10px", borderRadius: 100, background: "rgba(27,158,82,0.11)", font: "600 11.5px Pretendard", color: C.rising }}>{u.grade}</span>
                  <span style={{ font: "500 11px Pretendard", color: C.faint }}>{u.status}</span>
                </div>
                <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 9 }}>
                  가입 {u.joinedAt} · 등급 스냅샷 {u.gradeComputedAt ?? "없음(L0)"} · 어뷰징 플래그 {u.abuseFlagCount}건
                </div>
                <div style={{ display: "flex", gap: 34, marginTop: 20, paddingTop: 18, borderTop: `1px solid ${C.line}` }}>
                  <Stat k="활동 점수 AS" v={u.activeScore.toFixed(1)} />
                  <Stat k="신뢰도 TI" v={u.trustIndex.toFixed(2)} />
                  <Stat k="판정 완료" v={u.judgedCount} />
                  <Stat k="HIT / MISS (180일)" v={`${u.hitInWindow} / ${u.missInWindow}`} />
                </div>
                <div style={{ marginTop: 14, font: "400 11.5px ui-monospace, monospace", color: C.sub, lineHeight: 1.7 }}>
                  {u.basis.map((b) => <div key={b}>{b}</div>)}
                </div>
              </Card>

              <Card style={{ marginTop: 12, padding: 0, overflow: "hidden" }}>
                <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
                  <span style={{ font: "600 13px Pretendard" }}>점수 원장 <span style={{ font: "500 11px ui-monospace, monospace", color: C.faint, marginLeft: 6 }}>append-only</span></span>
                  <span style={{ font: "400 11.5px Pretendard", color: C.faint }}>수정·삭제 UI는 존재하지 않습니다. 정정은 ADJ 행 추가뿐입니다.</span>
                </div>
                {u.ledger.length === 0 && <div style={{ padding: "18px 20px", font: "500 12.5px Pretendard", color: C.faint }}>원장 행이 없습니다.</div>}
                {u.ledger.map((l) => (
                  <div key={l.id} style={{ display: "grid", gridTemplateColumns: "130px 56px 84px 1fr 150px", alignItems: "center", padding: "12px 20px", borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{l.createdAt}</span>
                    <span style={{ justifySelf: "start", font: "600 10.5px ui-monospace, monospace", padding: "4px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", color: KIND_COLOR[l.kind] }}>{l.kind}</span>
                    <span style={{ font: "700 13px ui-monospace, monospace", color: KIND_COLOR[l.kind] }}>{l.delta > 0 ? "+" : ""}{l.delta.toFixed(1)}</span>
                    <span style={{ font: "500 12px Pretendard", color: C.sub }}>{l.trendItemName ? `${l.trendItemName} · ` : ""}{l.reason}</span>
                    <span style={{ font: "500 11px Pretendard", color: C.faint }}>{l.approvedBy ? `승인: ${l.approvedBy}` : ""}</span>
                  </div>
                ))}
              </Card>

              <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
                <Btn tone="primary" disabled={!CAN.ledgerAdj(role)} title={!CAN.ledgerAdj(role) ? "OPERATOR 이상 필요" : undefined} onClick={() => setAdjOpen((o) => !o)}>상쇄 원장 추가</Btn>
                <Btn disabled title="미구현(Phase 2)">제재 상신</Btn>
                <Btn disabled title="미구현(Phase 2)">등급 수동 조정</Btn>
              </div>

              {adjOpen && CAN.ledgerAdj(role) && (
                <Card style={{ marginTop: 12 }}>
                  <b style={{ fontSize: 13.5 }}>상쇄 원장 추가 — 기존 행은 그대로 두고 ADJ 행만 덧붙입니다</b>
                  <div style={{ display: "flex", gap: 10, marginTop: 14, alignItems: "flex-start" }}>
                    <div style={{ width: 120, flex: "none" }}>
                      <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>Δ 점수</div>
                      <input value={amt} onChange={(e) => setAmt(e.target.value)} style={inp} />
                    </div>
                    <div style={{ flex: 1 }}>
                      <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>사유 (필수)</div>
                      <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: 수집 장애 구간 보정" style={inp} />
                    </div>
                  </div>
                  <div style={{ marginTop: 12, font: "500 12px Pretendard", color: needsApproval ? C.fading : C.sub }}>
                    {needsApproval ? "승인 대상 — 다른 ADMIN이 승인하면 반영됩니다(100점 초과 또는 OPERATOR 상신)." : "100점 이하 — 바로 기록됩니다. 사유와 금액이 감사 로그에 남습니다."}
                  </div>
                  <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
                    <Btn tone="primary" disabled={!adjReady || busy} onClick={submitAdj}>{needsApproval ? "승인 요청" : "ADJ 기록"}</Btn>
                    <Btn onClick={() => setAdjOpen(false)}>취소</Btn>
                  </div>
                </Card>
              )}
            </>
          )}
        </StateView>
      )}
      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const inp: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" };

function Stat({ k, v }: { k: string; v: React.ReactNode }) {
  return (
    <div>
      <div style={{ font: "400 11px Pretendard", color: C.faint }}>{k}</div>
      <div style={{ font: "700 24px Pretendard", letterSpacing: "-0.02em", marginTop: 8 }}>{v}</div>
    </div>
  );
}
