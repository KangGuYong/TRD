import React, { useState } from "react";
import { C } from "../theme";
import { Card, Btn, Label } from "../components/ui";
import { useAuth } from "../state/auth";

export default function LoginScreen() {
  const { state, login } = useAuth();
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const error = state.status === "unauthenticated" ? state.error : undefined;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!loginId || !password) return;
    setSubmitting(true);
    try {
      await login(loginId, password);
    } catch {
      /* 에러 메시지는 auth state에 이미 담김 */
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div style={{ height: "100vh", display: "flex", alignItems: "center", justifyContent: "center", background: C.bg, fontFamily: "Pretendard, system-ui, sans-serif" }}>
      <Card style={{ width: 340 }}>
        <div style={{ font: "700 16px Pretendard", color: C.ink, marginBottom: 4 }}>트렌드 관리자 콘솔</div>
        <div style={{ font: "500 12px Pretendard", color: C.faint, marginBottom: 22 }}>로그인이 필요합니다</div>
        <form onSubmit={submit}>
          <div style={{ marginBottom: 14 }}>
            <Label>아이디</Label>
            <input value={loginId} onChange={(e) => setLoginId(e.target.value)} autoFocus
              style={{ width: "100%", boxSizing: "border-box", padding: "10px 12px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 13px Pretendard" }} />
          </div>
          <div style={{ marginBottom: 18 }}>
            <Label>비밀번호</Label>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
              style={{ width: "100%", boxSizing: "border-box", padding: "10px 12px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 13px Pretendard" }} />
          </div>
          {error && (
            <div style={{ marginBottom: 14, padding: "9px 12px", borderRadius: 8, background: "rgba(216,72,60,0.08)", color: C.fading, font: "600 12px Pretendard" }}>
              {error}
            </div>
          )}
          <Btn tone="primary" disabled={submitting || !loginId || !password}>
            {submitting ? "로그인 중…" : "로그인"}
          </Btn>
        </form>
      </Card>
    </div>
  );
}
