import React, { useState } from "react";
import { useAdminUser } from "../api/hooks";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

const KIND_COLOR: Record<string, string> = { HIT: C.rising, MISS: C.fading, VOID: C.faint, ADJ: C.peak };

/** ADM-311. 원장 수정·삭제 UI는 존재하지 않는다 — 정정은 ADJ 추가뿐(R2). */
export default function UserLedgerScreen() {
  const q = useAdminUser("user_4410");
  const { role } = useRole();
  const [masked, setMasked] = useState(true);
  const [adjOpen, setAdjOpen] = useState(false);
  const [amt, setAmt] = useState("");
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);

  const over100 = Math.abs(parseFloat(amt || "0")) > 100;
  const adjReady = !!reason.trim() && !!amt.trim() && !isNaN(parseFloat(amt));
  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };

  return (
    <div style={{ maxWidth: 1000 }}>
      <StateView query={q}>
        {(u) => (
          <>
            <Card>
              <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 20 }}>
                <div>
                  <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <span style={{ font: "700 21px ui-monospace, monospace", letterSpacing: "-0.02em" }}>{u.userId}</span>
                    <span style={{ padding: "5px 10px", borderRadius: 100, background: "rgba(27,158,82,0.11)", font: "600 11.5px Pretendard", color: C.rising }}>{u.grade}</span>
                  </div>
                  <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 9 }}>가입 {u.joinedAt} · 어뷰징 플래그 {u.flags}건</div>
                </div>
                <Btn onClick={() => { setMasked((m) => !m); if (masked) flash("개인정보 열람 — 사유 기록·접속기록 남김"); }}>{masked ? "개인정보 열람" : "마스킹"}</Btn>
              </div>
              <div style={{ display: "flex", gap: 34, marginTop: 20, paddingTop: 18, borderTop: `1px solid ${C.line}` }}>
                <Stat k="활동 점수 AS" v={u.activeScore} />
                <Stat k="신뢰도 TI" v={u.trustIndex.toFixed(2)} />
                <Stat k="판정 완료" v={u.judgedCount} />
                <Stat k="HIT / MISS" v={`${u.hit} / ${u.miss}`} />
              </div>
              <div style={{ marginTop: 16, padding: "12px 14px", borderRadius: 10, font: "400 11.5px Pretendard", lineHeight: 1.5, background: masked ? "rgba(20,19,15,0.045)" : "rgba(216,72,60,0.07)", color: masked ? C.sub : C.ink }}>
                {masked ? "실명·연락처·디바이스 지문은 기본 마스킹됩니다." : "개인정보 표시 중 — 이 열람은 감사 로그에 기록됩니다."}
              </div>
            </Card>

            <Card style={{ marginTop: 12, padding: 0, overflow: "hidden" }}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "16px 20px", borderBottom: `1px solid ${C.line}` }}>
                <span style={{ font: "600 13px Pretendard" }}>점수 원장 <span style={{ font: "500 11px ui-monospace, monospace", color: C.faint, marginLeft: 6 }}>append-only</span></span>
                <span style={{ font: "400 11.5px Pretendard", color: C.faint }}>수정·삭제 UI는 존재하지 않습니다. 정정은 ADJ 행 추가뿐입니다.</span>
              </div>
              {u.ledger.map((l, i) => (
                <div key={i} style={{ display: "grid", gridTemplateColumns: "80px 64px 74px 1fr", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{l.date}</span>
                  <span style={{ justifySelf: "start", font: "600 10.5px ui-monospace, monospace", padding: "4px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", color: KIND_COLOR[l.kind] }}>{l.kind}</span>
                  <span style={{ font: "700 13px ui-monospace, monospace", color: KIND_COLOR[l.kind] }}>{l.delta}</span>
                  <span style={{ font: "500 12px Pretendard", color: C.sub }}>{l.reason}</span>
                </div>
              ))}
            </Card>

            <div style={{ display: "flex", gap: 8, marginTop: 12 }}>
              <Btn tone="primary" disabled={!CAN.ledgerAdj(role)} title={!CAN.ledgerAdj(role) ? "ADMIN 필요" : undefined} onClick={() => setAdjOpen((o) => !o)}>상쇄 원장 추가</Btn>
              <Btn disabled={!CAN.sanctionRequest(role)} onClick={() => flash("제재 상신 → ADMIN 2인 승인 대기")}>제재 상신 → ADMIN</Btn>
              <Btn disabled={!CAN.ledgerAdj(role)} onClick={() => flash("등급 수동 조정 — 2인 승인 · 다음 재계산 시 원복 경고")}>등급 수동 조정 (2인)</Btn>
            </div>

            {adjOpen && CAN.ledgerAdj(role) && (
              <Card style={{ marginTop: 12 }}>
                <b style={{ fontSize: 13.5 }}>상쇄 원장 추가 — 기존 판정은 그대로 두고 ADJ 행만 덧붙입니다</b>
                <div style={{ display: "flex", gap: 10, marginTop: 14, alignItems: "flex-start" }}>
                  <div style={{ width: 120, flex: "none" }}>
                    <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>Δ 점수</div>
                    <input value={amt} onChange={(e) => setAmt(e.target.value)} style={inp} />
                  </div>
                  <div style={{ flex: 1 }}>
                    <div style={{ font: "500 11px Pretendard", color: C.faint, marginBottom: 7 }}>사유 (필수)</div>
                    <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="예: X API 수집 장애 구간 보정" style={inp} />
                  </div>
                </div>
                <div style={{ marginTop: 12, font: "500 12px Pretendard", color: over100 ? C.fading : C.sub }}>
                  {over100 ? "100점 초과 — 2인 승인 대상입니다." : "100점 이하 — 단독 승인 가능. 사유는 감사 로그에 기록됩니다."}
                </div>
                <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
                  <Btn tone="primary" disabled={!adjReady} onClick={() => { flash(over100 ? "ADJ 2인 승인 대기 생성" : `ADJ ${amt} 기록됨`); setAdjOpen(false); setAmt(""); setReason(""); }}>{over100 ? "승인 요청" : "ADJ 기록"}</Btn>
                  <Btn onClick={() => setAdjOpen(false)}>취소</Btn>
                </div>
              </Card>
            )}
          </>
        )}
      </StateView>
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
