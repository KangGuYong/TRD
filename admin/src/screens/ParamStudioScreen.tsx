import React, { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";
import {
  fetchBacktestExample, requestParamApproval, runBacktest, simulateParamDraft, updateParamDraft,
  uploadBacktestDataset, useBacktestDatasets, useParamDraft,
} from "../api/hooks";
import type { BacktestCaseRow, BacktestSide, DraftValues, IndependenceMode, ParameterDraftView } from "../api/types";
import { Card, Btn } from "../components/ui";
import { C } from "../theme";
import { useRole, CAN } from "../state/role";

/**
 * ADM-600. 드래프트(9개 값) → 시뮬레이션 + 백테스트 → 2인 승인 → 즉시 운영값(이후 판정부터, 비소급).
 * 두 결과가 없으면 승인 요청 불가(서버가 막는다 — 버튼은 표시용).
 */
export default function ParamStudioScreen() {
  const { role } = useRole();
  const queryClient = useQueryClient();
  const { data: draft, isLoading } = useParamDraft();

  const [v, setV] = useState<DraftValues | null>(null);
  const [reason, setReason] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (draft) setV(draft.values);
  }, [draft?.draftId, JSON.stringify(draft?.values)]);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2600); };
  const onError = (e: unknown) => flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");
  const put = (next: ParameterDraftView) => queryClient.setQueryData(["admin", "param-draft"], next);
  const act = async (fn: () => Promise<void>) => {
    setBusy(true);
    try { await fn(); } catch (e) { onError(e); } finally { setBusy(false); }
  };

  if (isLoading || !draft || !v) return <div style={{ padding: 24, font: "500 13px Pretendard", color: C.faint }}>불러오는 중...</div>;

  const hasDraft = draft.draftId !== null;
  const locked = draft.status === "REVIEW";
  const canEdit = hasDraft && CAN.paramDraft(role) && !locked;
  const dirty = JSON.stringify(v) !== JSON.stringify(draft.values);
  const set = <K extends keyof DraftValues>(k: K, value: DraftValues[K]) => setV({ ...v, [k]: value });

  return (
    <div style={{ maxWidth: 1100 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, padding: "14px 18px", borderRadius: 12, background: "rgba(223,164,0,0.1)", border: "1px solid rgba(223,164,0,0.28)", marginBottom: 16 }}>
        <span style={{ font: "600 12.5px Pretendard" }}>파라미터는 즉시 반영되지 않습니다 — 드래프트 → 시뮬레이션·백테스트 → 2인 승인 → 이후 판정부터 적용(비소급).</span>
        <span style={{ marginLeft: "auto", font: "500 11.5px ui-monospace, monospace", color: C.faint }}>
          {hasDraft ? `드래프트 #${draft.draftId!.slice(0, 8)} · ${locked ? "승인 대기중" : "작성중"}` : "진행 중인 드래프트 없음 — 운영값"}
        </span>
      </div>

      {/* 좁으면 두 카드를 세로로 쌓는다 — 나란히 두면 입력 행 라벨이 한 글자씩 줄바꿈된다 */}
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(340px, 1fr))", gap: 12 }}>
        <Card>
          <b style={{ fontSize: 13 }}>판정 파라미터</b>
          <div style={{ font: "500 11.5px Pretendard", color: C.faint, marginTop: 6, lineHeight: 1.6 }}>
            T = 제보자 비율 × 지속성 × 다양성. 하한이 1이면 그 축은 꺼져 있습니다. 판정 입력은 제보뿐입니다(R5).
          </div>
          <Group title="목표치">
            <NumRow label="하한" hint="서로 다른 제보자" cur={draft.current.targetFloor} val={v.targetFloor} step={1} disabled={!canEdit} onChange={(x) => set("targetFloor", x)} />
            <NumRow label="비율" hint="활성 제보자 대비" cur={draft.current.targetRatio} val={v.targetRatio} step={0.01} disabled={!canEdit} onChange={(x) => set("targetRatio", x)} />
            <NumRow label="활성 기간(일)" hint="시뮬·백테스트엔 미반영" cur={draft.current.activeWindowDays} val={v.activeWindowDays} step={1} disabled={!canEdit} onChange={(x) => set("activeWindowDays", x)} />
          </Group>
          <Group title="판정">
            <NumRow label="HIT 임계값" cur={draft.current.hitThreshold} val={v.hitThreshold} step={0.01} disabled={!canEdit} onChange={(x) => set("hitThreshold", x)} />
          </Group>
          <Group title="지속성 — 며칠에 걸쳐 제보됐나(KST)">
            <NumRow label="하한" cur={draft.current.persistenceFloor} val={v.persistenceFloor} step={0.05} disabled={!canEdit} onChange={(x) => set("persistenceFloor", x)} />
            <NumRow label="기준일수" cur={draft.current.persistenceFullDays} val={v.persistenceFullDays} step={1} disabled={!canEdit} onChange={(x) => set("persistenceFullDays", x)} />
          </Group>
          <Group title="다양성 — 몇 군데에서 목격됐나(링크 판별)">
            <NumRow label="하한" cur={draft.current.diversityFloor} val={v.diversityFloor} step={0.05} disabled={!canEdit} onChange={(x) => set("diversityFloor", x)} />
            <NumRow label="기준 플랫폼 수" cur={draft.current.diversityFullPlatforms} val={v.diversityFullPlatforms} step={1} disabled={!canEdit} onChange={(x) => set("diversityFullPlatforms", x)} />
          </Group>
          <Group title="독립성 — 같은 기기·IP 계정 묶기">
            <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "8px 0" }}>
              <span style={{ font: "600 12.5px Pretendard" }}>모드</span>
              <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
                <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{MODE_LABEL[draft.current.independenceMode]} →</span>
                <select value={v.independenceMode} disabled={!canEdit} onChange={(e) => set("independenceMode", e.target.value as IndependenceMode)}
                  style={{ padding: "5px 8px", borderRadius: 7, border: `1px solid ${C.line}`, font: "600 12px Pretendard" }}>
                  {(Object.keys(MODE_LABEL) as IndependenceMode[]).map((m) => <option key={m} value={m}>{MODE_LABEL[m]}</option>)}
                </select>
              </span>
            </div>
            {v.independenceMode === "DEVICE_OR_IP" && (
              <div style={{ font: "500 11px Pretendard", color: C.fading, lineHeight: 1.5 }}>
                통신사·카페처럼 IP를 나눠 쓰는 정상 유저가 한 명으로 묶일 수 있습니다. 실사례 백테스트로 확인한 뒤에만 쓰세요.
              </div>
            )}
          </Group>
          {hasDraft && (
            <div style={{ marginTop: 14 }}>
              <Btn disabled={!canEdit || !dirty || busy} onClick={() => act(async () => {
                put(await updateParamDraft(v));
                flash("드래프트에 적용됨 — 시뮬레이션·백테스트를 다시 돌려야 합니다");
              })}>적용</Btn>
            </div>
          )}
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

      <BacktestCard draft={draft} canRun={CAN.paramDraft(role) && hasDraft && !locked && !dirty} busy={busy} act={act} put={put} flash={flash} />

      <div style={{ display: "flex", gap: 8, marginTop: 12, alignItems: "center", flexWrap: "wrap" }}>
        {hasDraft && (
          <>
            <Btn disabled={!CAN.paramDraft(role) || locked || dirty || busy} title={dirty ? "먼저 적용하세요" : undefined}
              onClick={() => act(async () => { put(await simulateParamDraft()); flash("시뮬레이션 완료 (과거 180일 재판정)"); })}>시뮬레이션 실행</Btn>
            <input placeholder="승인 요청 사유" value={reason} onChange={(e) => setReason(e.target.value)} disabled={!CAN.paramDraft(role) || locked}
              style={{ padding: "8px 10px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard", width: 220 }} />
            <Btn tone="primary" disabled={!draft.simResult || !draft.backtestResult || locked || dirty || !CAN.paramDraft(role) || busy}
              title={!draft.simResult ? "시뮬레이션 먼저" : !draft.backtestResult ? "백테스트 먼저" : undefined}
              onClick={() => act(async () => { put(await requestParamApproval(reason)); flash("승인 요청 생성됨 — 다른 ADMIN 1명의 승인 필요"); })}>
              승인 요청 (비소급)
            </Btn>
          </>
        )}
        <span style={{ marginLeft: "auto", font: "500 11.5px Pretendard", color: C.faint }}>
          적용 승인은 {CAN.paramApply(role) ? "가능(다른 ADMIN 1명의 승인)" : "다른 ADMIN 1명의 승인 필요"}
        </span>
      </div>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const MODE_LABEL: Record<IndependenceMode, string> = { OFF: "끔", DEVICE: "기기", DEVICE_OR_IP: "기기 또는 IP" };
const fmt = (x: number | null) => (x === null ? "—" : x.toFixed(2));

function BacktestCard({ draft, canRun, busy, act, put, flash }: {
  draft: ParameterDraftView; canRun: boolean; busy: boolean;
  act: (fn: () => Promise<void>) => Promise<void>; put: (d: ParameterDraftView) => void; flash: (m: string) => void;
}) {
  const queryClient = useQueryClient();
  const { data: datasets } = useBacktestDatasets();
  const [selected, setSelected] = useState<string>("");
  const [onlyChanged, setOnlyChanged] = useState(true);
  const fileRef = useRef<HTMLInputElement>(null);
  const r = draft.backtestResult;

  useEffect(() => {
    if (!selected && datasets && datasets.length > 0) setSelected(r?.datasetId ?? datasets[0].id);
  }, [datasets, r?.datasetId]);

  const onFile = (file: File) => act(async () => {
    const saved = await uploadBacktestDataset(await file.text());
    await queryClient.invalidateQueries({ queryKey: ["admin", "backtest-datasets"] });
    setSelected(saved.id);
    flash(`데이터셋 '${saved.name}' 준비됨 (${saved.caseCount}건)`);
  });

  const downloadExample = () => act(async () => {
    const data = await fetchBacktestExample();
    const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }));
    const a = document.createElement("a");
    a.href = url; a.download = "synthetic-v1.json"; a.click();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  });

  const rows: BacktestCaseRow[] = r ? r.report.rows.filter((row) => !onlyChanged || row.changed) : [];

  return (
    <Card style={{ marginTop: 12 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
        <b style={{ fontSize: 13 }}>백테스트 — 사례 파일로 현재 vs 초안</b>
        <span style={{ font: "500 11px Pretendard", color: C.faint }}>정답 라벨은 평가에만 씁니다 — 판정 입력은 제보뿐(R5)</span>
        <span style={{ marginLeft: "auto", display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
          <select value={selected} onChange={(e) => setSelected(e.target.value)}
            style={{ padding: "6px 8px", borderRadius: 7, border: `1px solid ${C.line}`, font: "500 12px Pretendard", maxWidth: 260 }}>
            {(datasets ?? []).length === 0 && <option value="">데이터셋 없음</option>}
            {(datasets ?? []).map((d) => <option key={d.id} value={d.id}>{d.name} · {d.caseCount}건 · {d.createdAt}</option>)}
          </select>
          <input ref={fileRef} type="file" accept="application/json,.json" style={{ display: "none" }}
            onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
          <Btn disabled={!canRun || busy} onClick={() => fileRef.current?.click()}>파일 올리기</Btn>
          <Btn disabled={busy} onClick={downloadExample}>예시 받기</Btn>
          <Btn tone="primary" disabled={!canRun || busy || !selected}
            title={!canRun ? "적용한 드래프트에서만 실행" : undefined}
            onClick={() => act(async () => { put(await runBacktest(selected)); flash("백테스트 완료"); })}>실행</Btn>
        </span>
      </div>

      {r ? (
        <>
          <div style={{ font: "500 11.5px Pretendard", color: C.sub, marginTop: 12 }}>
            '{r.datasetName}' {r.caseCount}건 · 해시 {r.sha256.slice(0, 12)} · 판정이 바뀐 사례 {r.report.changedCount}건
          </div>
          <div style={{ font: "500 10.5px Pretendard", color: C.faint, marginTop: 2 }}>
            정답 라벨은 사람이 붙인 평가용 값입니다. 예시(합성 시나리오) 파일의 라벨은 설계 의도일 뿐 실측이 아닙니다.
          </div>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(220px, 1fr))", gap: 10, marginTop: 10 }}>
            <SideBox title="현재 운영값" side={r.report.current} />
            <SideBox title="초안" side={r.report.draft} highlight />
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8, marginTop: 14 }}>
            <b style={{ fontSize: 12.5 }}>사례별</b>
            <label style={{ font: "500 11.5px Pretendard", color: C.sub }}>
              <input type="checkbox" checked={onlyChanged} onChange={(e) => setOnlyChanged(e.target.checked)} /> 바뀐 사례만
            </label>
          </div>
          <div style={{ marginTop: 6 }}>
            {rows.map((row) => (
              <details key={row.caseId} style={{ padding: "9px 0", borderBottom: `1px solid ${C.line}` }}>
                <summary style={{ cursor: "pointer", display: "flex", gap: 10, alignItems: "center", font: "500 12px Pretendard" }}>
                  <span style={{ font: "600 11px ui-monospace, monospace", color: C.faint, width: 56 }}>{row.caseId}</span>
                  <span style={{ flex: 1 }}>{row.title}</span>
                  <span style={{ font: "600 11px ui-monospace, monospace" }}>정답 {row.label}{row.labelReach ? ` ${row.labelReach}` : ""}</span>
                  <Verdict o={row.current} label={row.label} />
                  <span style={{ color: C.faint }}>→</span>
                  <Verdict o={row.draft} label={row.label} />
                </summary>
                <div style={{ font: "500 11px ui-monospace, monospace", color: C.sub, marginTop: 6, lineHeight: 1.7 }}>
                  <div>현재 · {row.current.explain}</div>
                  <div>초안 · {row.draft.explain}</div>
                </div>
              </details>
            ))}
            {rows.length === 0 && <div style={{ padding: "12px 0", font: "500 12px Pretendard", color: C.faint }}>표시할 사례가 없습니다.</div>}
          </div>
        </>
      ) : (
        <div style={{ padding: "22px 0", textAlign: "center", font: "500 13px Pretendard", color: C.faint, lineHeight: 1.6 }}>
          백테스트를 돌리기 전에는 승인 요청을 보낼 수 없습니다. 실사례가 없으면 "예시 받기"의 합성 시나리오로 형식을 확인하세요.
        </div>
      )}
    </Card>
  );
}

function SideBox({ title, side, highlight }: { title: string; side: BacktestSide; highlight?: boolean }) {
  const c = side.confusion;
  return (
    <div style={{ padding: "12px 14px", borderRadius: 10, background: highlight ? "rgba(223,164,0,0.08)" : "rgba(20,19,15,0.03)" }}>
      <div style={{ font: "600 12px Pretendard" }}>{title}</div>
      <div style={{ display: "flex", gap: 18, marginTop: 8, font: "700 18px ui-monospace, monospace" }}>
        <span>정밀도 {fmt(side.precision)}</span>
        <span>재현율 {fmt(side.recall)}</span>
      </div>
      <div style={{ font: "500 11px ui-monospace, monospace", color: C.sub, marginTop: 6 }}>
        TP {c.tp} · FP {c.fp} · FN {c.fn} · TN {c.tn} · 확산 등급 일치 {fmt(side.reachAgreement)} ({side.reachCompared}건 비교)
      </div>
    </div>
  );
}

function Verdict({ o, label }: { o: { result: string; reach: string | null }; label: string }) {
  const ok = o.result === label;
  return (
    <span style={{ font: "600 11px ui-monospace, monospace", padding: "2px 6px", borderRadius: 5,
      background: ok ? "rgba(27,158,82,0.1)" : "rgba(216,72,60,0.1)", color: ok ? C.rising : C.fading }}>
      {o.result}{o.reach ? ` ${o.reach}` : ""}
    </span>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginTop: 14, paddingTop: 12, borderTop: `1px solid ${C.line}` }}>
      <div style={{ font: "600 11.5px Pretendard", color: C.sub, marginBottom: 2 }}>{title}</div>
      {children}
    </div>
  );
}

function NumRow({ label, hint, cur, val, step, disabled, onChange }: {
  label: string; hint?: string; cur: number; val: number; step: number; disabled: boolean; onChange: (x: number) => void;
}) {
  return (
    <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "6px 0" }}>
      <span style={{ font: "600 12.5px Pretendard" }}>
        {label}{hint && <span style={{ font: "500 11px Pretendard", color: C.faint, marginLeft: 6 }}>{hint}</span>}
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 8 }}>
        <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{cur} →</span>
        <input type="number" value={val} step={step} disabled={disabled}
          onChange={(e) => onChange(e.target.value === "" ? 0 : Number(e.target.value))}
          style={{ width: 80, padding: "5px 8px", borderRadius: 7, border: `1px solid ${C.line}`,
            font: "600 12.5px ui-monospace, monospace", color: val !== cur ? C.peak : C.ink }} />
      </span>
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
