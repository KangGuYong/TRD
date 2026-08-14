import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAdminAccounts, createAdminAccount, setAdminAccountDisabled } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C, ROLE_LABEL, type Role } from "../theme";
import { useRole, CAN } from "../state/role";
import type { AdminAccountSummary } from "../api/types";

const ROLES: Role[] = ["REVIEWER", "OPERATOR", "ADMIN", "AUDITOR"];

/** ADM-800. 역할 부여/회수는 문서상 2인 승인 대상이나, 그 워크플로(approval_requests) 자체가
 * 아직 없어 이번 스코프는 ADMIN 단독 처리 — 의도된 축소(감사 로그에는 남음). */
export default function AdminAccountsScreen() {
  const q = useAdminAccounts();
  const { role } = useRole();
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [loginId, setLoginId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [newRole, setNewRole] = useState<Role>("REVIEWER");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const flash = (m: string) => { setToast(m); setTimeout(() => setToast(null), 2800); };
  const canManage = CAN.accountManage(role);

  const resetForm = () => { setLoginId(""); setDisplayName(""); setNewRole("REVIEWER"); setPassword(""); setError(null); };

  const submitCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    if (USE_FIXTURES) {
      flash(`(데모) ${loginId} 계정 생성됨`);
      setCreateOpen(false);
      resetForm();
      return;
    }
    setSubmitting(true);
    try {
      await createAdminAccount({ loginId, displayName, role: newRole, password });
      await qc.invalidateQueries({ queryKey: ["admin", "accounts"] });
      flash(`${loginId} 계정을 만들었습니다`);
      setCreateOpen(false);
      resetForm();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "계정 생성에 실패했습니다");
    } finally {
      setSubmitting(false);
    }
  };

  const toggleDisabled = async (acc: AdminAccountSummary) => {
    const nextDisabled = !acc.disabledAt;
    if (USE_FIXTURES) {
      flash(`(데모) ${acc.loginId} ${nextDisabled ? "비활성화" : "재활성화"}됨`);
      return;
    }
    try {
      await setAdminAccountDisabled(acc.id, nextDisabled);
      await qc.invalidateQueries({ queryKey: ["admin", "accounts"] });
      flash(`${acc.loginId} ${nextDisabled ? "비활성화" : "재활성화"}됨`);
    } catch (e) {
      flash(e instanceof ApiError ? e.message : "처리에 실패했습니다");
    }
  };

  return (
    <div style={{ maxWidth: 1000 }}>
      <div style={{ display: "flex", justifyContent: "flex-end", marginBottom: 12 }}>
        <Btn tone="primary" disabled={!canManage} title={!canManage ? "ADMIN 필요" : undefined}
          onClick={() => setCreateOpen((o) => !o)}>+ 계정 추가</Btn>
      </div>

      {createOpen && canManage && (
        <Card style={{ marginBottom: 12 }}>
          <b style={{ fontSize: 13.5 }}>관리자 계정 추가</b>
          <form onSubmit={submitCreate}>
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginTop: 14 }}>
              <div>
                <Label>아이디</Label>
                <input value={loginId} onChange={(e) => setLoginId(e.target.value)} style={inp} />
              </div>
              <div>
                <Label>이름</Label>
                <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} style={inp} />
              </div>
              <div>
                <Label>역할</Label>
                <select value={newRole} onChange={(e) => setNewRole(e.target.value as Role)} style={inp}>
                  {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]} ({r})</option>)}
                </select>
              </div>
              <div>
                <Label>초기 비밀번호 (8자 이상)</Label>
                <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} style={inp} />
              </div>
            </div>
            {error && (
              <div style={{ marginTop: 12, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{error}</div>
            )}
            <div style={{ display: "flex", gap: 8, marginTop: 14 }}>
              <Btn tone="primary" disabled={submitting || !loginId || !displayName || password.length < 8}>
                {submitting ? "생성 중…" : "생성"}
              </Btn>
              <Btn onClick={() => { setCreateOpen(false); resetForm(); }}>취소</Btn>
            </div>
          </form>
        </Card>
      )}

      <Card style={{ padding: 0, overflow: "hidden" }}>
        <div style={{ display: "grid", gridTemplateColumns: "120px 110px 90px 130px 130px 90px", padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["아이디", "이름", "역할", "마지막 로그인", "상태", ""].map((h) => (
            <span key={h} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => (
            <>
              {rows.map((a) => (
                <div key={a.id} style={{ display: "grid", gridTemplateColumns: "120px 110px 90px 130px 130px 90px", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                  <span style={{ font: "600 12.5px Pretendard" }}>{a.loginId}</span>
                  <span style={{ font: "500 12.5px Pretendard" }}>{a.displayName}</span>
                  <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.sub }}>{a.role}</span>
                  <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{a.lastLoginAt ?? "-"}</span>
                  <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: a.disabledAt ? "rgba(216,72,60,0.1)" : "rgba(27,158,82,0.1)", color: a.disabledAt ? C.fading : C.rising }}>
                    {a.disabledAt ? "비활성" : "활성"}
                  </span>
                  <Btn disabled={!canManage} title={!canManage ? "ADMIN 필요" : undefined}
                    onClick={() => toggleDisabled(a)}>{a.disabledAt ? "재활성화" : "비활성화"}</Btn>
                </div>
              ))}
              <div style={{ padding: "14px 20px", font: "400 11.5px Pretendard", color: C.faint }}>
                생성·비활성화는 감사 로그에 기록됩니다. 역할 부여/회수의 2인 승인 워크플로는 아직 없어 ADMIN 단독 처리입니다.
              </div>
            </>
          )}
        </StateView>
      </Card>

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const inp: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" };
