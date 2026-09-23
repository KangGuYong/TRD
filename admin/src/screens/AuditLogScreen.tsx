import React, { useState } from "react";
import { useAuditLog } from "../api/hooks";
import { Card, StateView, Btn } from "../components/ui";
import { C } from "../theme";
import type { AuditEntry, AuditFilter } from "../api/types";

const COLS = "70px 100px 80px 170px 1fr 130px";

/** ADM-700. 서버가 기록하는 관리자 개입(변경)과 로그인. append-only · 해시 체인. OPERATOR는 본인 행만 보인다. */
export default function AuditLogScreen() {
  const [draft, setDraft] = useState<AuditFilter>({});
  const [filter, setFilter] = useState<AuditFilter>({});
  const [cursor, setCursor] = useState<number | null>(null);
  const [older, setOlder] = useState<AuditEntry[]>([]);
  const [open, setOpen] = useState<number | null>(null);
  const q = useAuditLog(filter, cursor);

  const apply = () => { setOlder([]); setCursor(null); setFilter(draft); };
  const more = (rows: AuditEntry[], next: number | null) => { setOlder((o) => [...o, ...rows]); setCursor(next); };

  return (
    <div style={{ maxWidth: 1100 }}>
      <Card style={{ marginBottom: 12, display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <input placeholder="액션 (예: LEDGER_ADJ)" value={draft.action ?? ""} onChange={(e) => setDraft({ ...draft, action: e.target.value || undefined })} style={inp} />
        <input placeholder="대상 ID" value={draft.targetId ?? ""} onChange={(e) => setDraft({ ...draft, targetId: e.target.value || undefined })} style={{ ...inp, width: 300 }} />
        <input type="datetime-local" onChange={(e) => setDraft({ ...draft, from: e.target.value ? new Date(e.target.value).toISOString() : undefined })} style={inp} />
        <span style={{ color: C.faint }}>~</span>
        <input type="datetime-local" onChange={(e) => setDraft({ ...draft, to: e.target.value ? new Date(e.target.value).toISOString() : undefined })} style={inp} />
        <Btn tone="primary" onClick={apply}>조회</Btn>
      </Card>

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: COLS, padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["ID", "관리자", "역할", "액션", "대상", "일시"].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(page) => {
            const rows = [...older, ...page.items];
            return (
              <>
                {rows.length === 0 && <div style={{ padding: "22px 20px", font: "500 12.5px Pretendard", color: C.faint }}>기록이 없습니다.</div>}
                {rows.map((r) => (
                  <div key={r.id} style={{ borderBottom: "1px solid rgba(20,19,15,0.05)" }}>
                    <div onClick={() => setOpen(open === r.id ? null : r.id)} style={{ display: "grid", gridTemplateColumns: COLS, alignItems: "center", padding: "13px 20px", cursor: "pointer" }}>
                      <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.id}</span>
                      <span style={{ font: "500 12.5px Pretendard" }}>{r.actor}</span>
                      <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.sub }}>{r.role}</span>
                      <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", justifySelf: "start", color: r.action.startsWith("SLA_") ? C.peak : C.ink }}>{r.action}</span>
                      <span style={{ font: "500 12px Pretendard", color: C.sub }}>{r.targetType} {r.targetId}</span>
                      <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.createdAt}</span>
                    </div>
                    {open === r.id && (
                      <pre style={{ margin: 0, padding: "0 20px 14px 90px", font: "500 11.5px ui-monospace, monospace", color: C.sub, whiteSpace: "pre-wrap" }}>
                        {JSON.stringify(r.detail, null, 2)}
                      </pre>
                    )}
                  </div>
                ))}
                <div style={{ padding: "14px 20px", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
                  <span style={{ font: "400 11.5px Pretendard", color: C.faint }}>관리자의 변경·로그인과 sla_watch 자동 조치가 기록됩니다. 조회 행위는 기록하지 않습니다.</span>
                  {page.nextBeforeId !== null && <Btn onClick={() => more(page.items, page.nextBeforeId)}>더 보기</Btn>}
                </div>
              </>
            );
          }}
        </StateView>
      </Card>
    </div>
  );
}

const inp: React.CSSProperties = { padding: "9px 11px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12px Pretendard" };
