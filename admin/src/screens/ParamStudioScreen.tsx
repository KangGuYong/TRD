import React, { useMemo, useState } from "react";
import { fxParams, fxSimulation } from "../fixtures";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

const WK: (keyof typeof fxParams.weights)[] = ["S1", "S2", "S3", "S4", "S5"];
const WNAME: Record<string, string> = { S1: "X 고참여", S2: "디시 언급", S3: "검색량", S4: "인스타", S5: "파생 생성" };

/** ADM-600. 드래프트 → 시뮬 → 2인 승인 → 예약. 시뮬 없이는 승인요청 불가(안전장치). */
export default function ParamStudioScreen() {
  const { role } = useRole();
  const base = fxParams.weights;
  const [w, setW] = useState({ ...base });
  const [hit, setHit] = useState(fxParams.hitThreshold);
  const [simDone, setSimDone] = useState(false);
  const [toast, setToast] = useState<string | null>(null);

  const sum = useMemo(() => WK.reduce((a, k) => a + w[k], 0), [w]);
  const sumOk = Math.abs(sum - 1) < 1e-9;

  const bump = (k: keyof typeof base, d: number) => { setW((p) => ({ ...p, [k]: Math.round((p[k] + d) * 100) / 100 })); setSimDone(false); };
  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderRadius: 12, background: "rgba(223,164,0,0.1)", border: "1px solid rgba(223,164,0,0.28)", marginBottom: 16 }}>
        <span style={{ font: "600 12.5px Pretendard" }}>파라미터는 즉시 반영되지 않습니다 — 드래프트 → 시뮬레이션 → 2인 승인 → 예약 적용.</span>
        <span style={{ marginLeft: "auto", font: "500 11.5px ui-monospace, monospace", color: C.faint }}>드래프트 #17 · 작성 김운영 · 검토중</span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 12 }}>
        <Card>
          <b style={{ fontSize: 13 }}>지표 가중치</b>
          <div style={{ display: "flex", flexDirection: "column", gap: 12, marginTop: 16 }}>
            {WK.map((k) => {
              const changed = w[k] !== base[k];
              return (
                <div key={k} style={{ display: "flex", alignItems: "center", gap: 12 }}>
                  <span style={{ width: 96, flex: "none" }}>
                    <span style={{ display: "block", font: "600 12.5px Pretendard" }}>{WNAME[k]}</span>
                    <span style={{ display: "block", font: "500 10px ui-monospace, monospace", color: C.faint, marginTop: 4 }}>{k}</span>
                  </span>
                  <span style={{ flex: 1, height: 5, borderRadius: 9, background: "rgba(20,19,15,0.08)", overflow: "hidden" }}>
                    <span style={{ display: "block", height: "100%", borderRadius: 9, width: `${w[k] * 100 * 2}%`, background: changed ? C.peak : C.ink }} />
                  </span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint, width: 32, textAlign: "right" }}>{base[k].toFixed(2)}</span>
                  <span style={{ font: "600 10px Pretendard", color: C.faint }}>→</span>
                  <span style={{ font: "700 13px ui-monospace, monospace", width: 40, textAlign: "right", color: changed ? C.peak : C.ink }}>{w[k].toFixed(2)}</span>
                  <span style={{ display: "flex", gap: 3, flex: "none" }}>
                    <MiniBtn disabled={!CAN.paramDraft(role)} onClick={() => bump(k, -0.05)}>−</MiniBtn>
                    <MiniBtn disabled={!CAN.paramDraft(role)} onClick={() => bump(k, 0.05)}>+</MiniBtn>
                  </span>
                </div>
              );
            })}
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginTop: 18, paddingTop: 14, borderTop: `1px solid ${C.line}` }}>
            <b style={{ fontSize: 12.5 }}>합계</b>
            <b style={{ font: "700 15px ui-monospace, monospace", color: sumOk ? C.rising : C.fading }}>{sum.toFixed(2)}</b>
          </div>
          {!sumOk && <div style={{ font: "500 11.5px Pretendard", marginTop: 8, color: C.fading }}>합계가 1.00이어야 저장할 수 있습니다.</div>}
          <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", marginTop: 14 }}>
            <span style={{ font: "600 12.5px Pretendard" }}>판정 임계값 (HIT)</span>
            <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
              <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{fxParams.hitThreshold.toFixed(2)} →</span>
              <span style={{ font: "700 13px ui-monospace, monospace", color: hit !== fxParams.hitThreshold ? C.peak : C.ink }}>{hit.toFixed(2)}</span>
              <MiniBtn disabled={!CAN.paramDraft(role)} onClick={() => { setHit((v) => Math.max(0, Math.round((v - 0.01) * 100) / 100)); setSimDone(false); }}>−</MiniBtn>
              <MiniBtn disabled={!CAN.paramDraft(role)} onClick={() => { setHit((v) => Math.round((v + 0.01) * 100) / 100); setSimDone(false); }}>+</MiniBtn>
            </span>
          </div>
        </Card>

        <Card>
          <b style={{ fontSize: 13 }}>시뮬레이션 — 과거 180일 재판정</b>
          {simDone ? (
            <div style={{ marginTop: 16 }}>
              <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
                <span style={{ font: "700 32px Pretendard", letterSpacing: "-0.03em" }}>{fxSimulation.changed}</span>
                <span style={{ font: "500 12.5px Pretendard", color: C.sub }}>/ {fxSimulation.total}건 판정 변동 ({Math.round((fxSimulation.changed / fxSimulation.total) * 100)}%)</span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 9, marginTop: 18 }}>
                {fxSimulation.rows.map((r, i) => {
                  const col = r.tone === "up" ? C.rising : r.tone === "down" ? C.fading : C.ink;
                  const bg = r.tone === "up" ? "rgba(27,158,82,0.09)" : r.tone === "down" ? "rgba(216,72,60,0.08)" : "rgba(20,19,15,0.04)";
                  return (
                    <div key={i} style={{ display: "flex", alignItems: "center", gap: 10, padding: "11px 13px", borderRadius: 10, background: bg }}>
                      <span style={{ font: "600 12.5px Pretendard", color: col }}>{r.label}</span>
                      <span style={{ marginLeft: "auto", font: "700 13px ui-monospace, monospace", color: col }}>
                        {i === 2 ? `강등 ${fxSimulation.demotions} / 승급 ${fxSimulation.promotions}` : `${r.count}건`}
                      </span>
                    </div>
                  );
                })}
              </div>
              <div style={{ marginTop: 12, padding: "12px 13px", borderRadius: 10, background: "rgba(216,72,60,0.07)", font: "500 11.5px Pretendard", lineHeight: 1.55 }}>
                영향 유저 {fxSimulation.affectedUsers}명 · 평균 {fxSimulation.avgDelta}점. 소급 적용 시 사전 통보 대상입니다.
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
        <Btn disabled={!CAN.paramDraft(role) || !sumOk} onClick={() => { setSimDone(true); flash("시뮬레이션 완료 (과거 180일 재판정)"); }}>시뮬레이션 실행</Btn>
        <Btn tone="primary" disabled={!simDone || !CAN.paramDraft(role)} title={!simDone ? "시뮬레이션 먼저" : undefined} onClick={() => flash("승인 요청 생성됨 (0/2) · 기본 예약 적용")}>승인 요청 (예약·비소급)</Btn>
        <span style={{ marginLeft: "auto", font: "500 11.5px Pretendard", color: C.faint }}>
          적용 승인은 {CAN.paramApply(role) ? "가능(ADMIN 2인)" : "ADMIN 2인 필요"}
        </span>
      </div>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

function MiniBtn({ children, onClick, disabled }: { children: React.ReactNode; onClick: () => void; disabled?: boolean }) {
  return (
    <button onClick={onClick} disabled={disabled} style={{ width: 24, height: 24, borderRadius: 6, border: "1px solid rgba(20,19,15,0.12)", background: "#fff", cursor: disabled ? "not-allowed" : "pointer", font: "600 13px Pretendard", color: C.ink, opacity: disabled ? 0.5 : 1 }}>{children}</button>
  );
}
