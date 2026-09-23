import React, { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useAdminAccounts, createAdminAccount, changeAdminRole, setAdminAccountDisabled } from "../api/hooks";
import { ApiError, USE_FIXTURES } from "../api/client";
import { Card, StateView, Btn, Label } from "../components/ui";
import { C, ROLE_LABEL, type Role } from "../theme";
import { useRole, CAN } from "../state/role";
import { useAuth } from "../state/auth";
import type { AdminAccountSummary } from "../api/types";

const ROLES: Role[] = ["REVIEWER", "OPERATOR", "ADMIN", "AUDITOR"];

/** ADM-800. 생성·역할 변경은 다른 ADMIN의 승인 후 반영(SP3). 승인할 사람이 없을 때만 생성 즉시 활성(부트스트랩 예외). */
export default function AdminAccountsScreen() {
  const q = useAdminAccounts();
  const { role } = useRole();
  const { state: authState } = useAuth();
  const principal = authState.status === "authenticated" ? authState.principal : null;
  const qc = useQueryClient();
  const [createOpen, setCreateOpen] = useState(false);
  const [loginId, setLoginId] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [newRole, setNewRole] = useState<Role>("REVIEWER");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const [roleChangeFor, setRoleChangeFor] = useState<AdminAccountSummary | null>(null);
  const [roleChangeRole, setRoleChangeRole] = useState<Role>("REVIEWER");
  const [roleChangeReason, setRoleChangeReason] = useState("");
  const [roleChangeBusy, setRoleChangeBusy] = useState(false);
  const [roleChangeError, setRoleChangeError] = useState<string | null>(null);

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
      const r = await createAdminAccount({ loginId, displayName, role: newRole, password });
      await qc.invalidateQueries({ queryKey: ["admin", "accounts"] });
      flash(r.status === "PENDING_APPROVAL" ? `${loginId} — 승인 대기(다른 ADMIN 승인 후 로그인 가능)` : `${loginId} 계정을 만들었습니다(승인할 ADMIN이 없어 즉시 활성)`);
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

  const openRoleChange = (acc: AdminAccountSummary) => {
    setRoleChangeFor(acc);
    setRoleChangeRole(acc.role as Role);
    setRoleChangeReason("");
    setRoleChangeError(null);
  };

  const submitRoleChange = async () => {
    if (!roleChangeFor) return;
    if (!roleChangeReason.trim()) { setRoleChangeError("사유는 필수입니다"); return; }
    if (USE_FIXTURES) { flash(`(데모) ${roleChangeFor.loginId} 역할 변경 승인 대기로 올렸습니다`); setRoleChangeFor(null); return; }
    setRoleChangeBusy(true);
    setRoleChangeError(null);
    try {
      await changeAdminRole(roleChangeFor.id, roleChangeRole, roleChangeReason.trim());
      await qc.invalidateQueries({ queryKey: ["admin", "accounts"] });
      flash(`${roleChangeFor.loginId} — 승인 대기로 올렸습니다`);
      setRoleChangeFor(null);
    } catch (e) {
      setRoleChangeError(e instanceof ApiError ? e.message : "역할 변경 요청에 실패했습니다");
    } finally {
      setRoleChangeBusy(false);
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
        <div style={{ display: "grid", gridTemplateColumns: "110px 100px 80px 120px 150px 90px 100px", padding: "13px 20px", borderBottom: `1px solid ${C.line}`, background: "rgba(20,19,15,0.02)" }}>
          {["아이디", "이름", "역할", "마지막 로그인", "상태", "", ""].map((h, i) => (
            <span key={i} style={{ font: "600 10.5px Pretendard", letterSpacing: ".06em", color: C.faint }}>{h}</span>
          ))}
        </div>
        <StateView query={q}>
          {(rows) => (
            <>
              {rows.map((a) => {
                const isSelf = principal != null && a.id === principal.id;
                const approverSinceAt = parseKst(a.approverSince);
                const approverPending = a.role === "ADMIN" && approverSinceAt !== null && approverSinceAt > new Date();
                return (
                  <div key={a.id} style={{ display: "grid", gridTemplateColumns: "110px 100px 80px 120px 150px 90px 100px", alignItems: "center", padding: "14px 20px", borderBottom: `1px solid rgba(20,19,15,0.05)` }}>
                    <span style={{ font: "600 12.5px Pretendard" }}>{a.loginId}</span>
                    <span style={{ font: "500 12.5px Pretendard" }}>{a.displayName}</span>
                    <span style={{ font: "600 10.5px ui-monospace, monospace", color: C.sub }}>{a.role}</span>
                    <span style={{ font: "500 11.5px ui-monospace, monospace", color: C.faint }}>{a.lastLoginAt ?? "-"}</span>
                    <span style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                      <span style={{ font: "600 11px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, justifySelf: "start", background: a.disabledAt ? "rgba(216,72,60,0.1)" : "rgba(27,158,82,0.1)", color: a.disabledAt ? C.fading : C.rising, width: "fit-content" }}>
                        {a.disabledAt ? "비활성" : "활성"}
                      </span>
                      {a.pendingApproval && (
                        <span style={{ font: "600 10.5px ui-monospace, monospace", padding: "3px 6px", borderRadius: 5, background: "rgba(223,164,0,0.12)", color: C.peak, width: "fit-content" }}>승인 대기</span>
                      )}
                      {approverPending && (
                        <span style={{ font: "500 10.5px ui-monospace, monospace", color: C.faint }}>승인권 {a.approverSince}부터</span>
                      )}
                    </span>
                    <Btn disabled={!canManage} title={!canManage ? "ADMIN 필요" : undefined}
                      onClick={() => toggleDisabled(a)}>{a.disabledAt ? "재활성화" : "비활성화"}</Btn>
                    <Btn disabled={!canManage || isSelf} title={isSelf ? "본인 역할은 바꿀 수 없습니다" : !canManage ? "ADMIN 필요" : undefined}
                      onClick={() => openRoleChange(a)}>역할 변경</Btn>
                  </div>
                );
              })}
              <div style={{ padding: "14px 20px", font: "400 11.5px Pretendard", color: C.faint }}>
                생성·역할 변경은 승인 대기함(ADM-620)을 거칩니다. 비활성화는 즉시 적용됩니다.
              </div>
            </>
          )}
        </StateView>
      </Card>

      {roleChangeFor && (
        <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 10 }}>
          <Card style={{ width: 380 }}>
            <b style={{ fontSize: 14 }}>{roleChangeFor.loginId} — 역할 변경</b>
            <div style={{ marginTop: 14 }}>
              <Label>새 역할</Label>
              <select value={roleChangeRole} onChange={(e) => setRoleChangeRole(e.target.value as Role)} style={inp}>
                {ROLES.map((r) => <option key={r} value={r}>{ROLE_LABEL[r]} ({r})</option>)}
              </select>
            </div>
            <div style={{ marginTop: 14 }}>
              <Label>사유 (필수)</Label>
              <input value={roleChangeReason} onChange={(e) => setRoleChangeReason(e.target.value)} style={inp} />
            </div>
            {roleChangeError && (
              <div style={{ marginTop: 12, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>{roleChangeError}</div>
            )}
            <div style={{ display: "flex", gap: 8, marginTop: 16 }}>
              <Btn tone="primary" disabled={roleChangeBusy || !roleChangeReason.trim()} onClick={submitRoleChange}>{roleChangeBusy ? "처리 중…" : "승인 요청"}</Btn>
              <Btn onClick={() => setRoleChangeFor(null)}>취소</Btn>
            </div>
          </Card>
        </div>
      )}

      {toast && <div style={{ position: "fixed", bottom: 24, left: "50%", transform: "translateX(-50%)", background: C.ink, color: "#fff", padding: "12px 18px", borderRadius: 10, font: "500 12.5px Pretendard" }}>{toast}</div>}
    </div>
  );
}

const inp: React.CSSProperties = { width: "100%", boxSizing: "border-box", padding: "11px 13px", borderRadius: 9, border: "1px solid rgba(20,19,15,0.12)", background: "#FBFAF7", outline: "none", font: "500 12.5px Pretendard" };

/** 서버가 "yyyy-MM-dd HH:mm"(KST, 오프셋 없음)로 주는 표시용 문자열을 KST로 명시 파싱한다.
 * new Date(문자열)에 그대로 넘기면 브라우저 로컬 타임존으로 해석돼 KST가 아닌 환경에서 틀어진다. */
function parseKst(s: string | null): Date | null {
  if (!s) return null;
  return new Date(s.replace(" ", "T") + ":00+09:00");
}
