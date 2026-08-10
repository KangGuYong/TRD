import React from "react";
import { useAuditLog } from "../api/hooks";
import { Card, StateView } from "../components/ui";
import { C } from "../theme";

/** ADM-700. 조회행위 포함 · append-only. 초기부터 없으면 소급 증명 불가. */
export default function AuditLogScreen() {
  const q = useAuditLog();
  return (
    <div style={{ maxWidth: 1000 }}>
      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: "70px 90px 90px 130px 1fr 140px", padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["ID", "관리자", "역할", "액션", "대상", "일시"].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => (
            <>
              {rows.map((r) => (
                <div key={r.id} style={{ display: "grid", gridTemplateColumns: "70px 90px 90px 130px 1fr 140px", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.id}</span>
                  <span style={{ font: "500 12.5px Pretendard" }}>{r.actor}</span>
                  <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.sub }}>{r.role}</span>
                  <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, background: "rgba(20,19,15,0.05)", justifySelf: "start", color: r.action === "PII_VIEW" ? C.peak : C.ink }}>{r.action}</span>
                  <span style={{ font: "500 12px Pretendard", color: C.sub }}>{r.targetType} {r.targetId}</span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{r.createdAt}</span>
                </div>
              ))}
              <div style={{ padding: "14px 20px", font: "400 11.5px Pretendard", color: C.faint }}>
                조회행위(PII_VIEW 등)도 기록됩니다. 보존·위변조 방지(해시 체인)는 백엔드 audit 모듈 담당.
              </div>
            </>
          )}
        </StateView>
      </Card>
    </div>
  );
}
