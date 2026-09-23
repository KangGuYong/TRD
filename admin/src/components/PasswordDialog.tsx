import React, { useState } from "react";
import { changeMyPassword } from "../api/hooks";
import { ApiError } from "../api/client";
import { Card, Btn } from "./ui";
import { C } from "../theme";

/** 내 비밀번호 변경(POST /admin/me/password). 부트스트랩 비밀번호를 바꾸는 유일한 수단. */
export default function PasswordDialog({ onClose }: { onClose: () => void }) {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const submit = async () => {
    setError(null);
    if (next.length < 8) { setError("새 비밀번호는 8자 이상이어야 합니다"); return; }
    if (next !== confirm) { setError("새 비밀번호 확인이 다릅니다"); return; }
    try {
      await changeMyPassword(current, next);
      setDone(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "변경에 실패했습니다");
    }
  };

  return (
    <div style={{ position: "fixed", inset: 0, background: "rgba(20,19,15,0.35)", display: "flex", alignItems: "center", justifyContent: "center", zIndex: 20 }}>
      <Card style={{ width: 380 }}>
        <b style={{ fontSize: 14 }}>비밀번호 변경</b>
        {done ? (
          <div style={{ marginTop: 14, font: "500 12.5px Pretendard" }}>변경했습니다. 다음 로그인부터 새 비밀번호를 쓰세요.</div>
        ) : (
          <>
            {[["현재 비밀번호", current, setCurrent], ["새 비밀번호(8자 이상)", next, setNext], ["새 비밀번호 확인", confirm, setConfirm]].map(([label, value, set]) => (
              <input key={label as string} type="password" placeholder={label as string} value={value as string}
                onChange={(e) => (set as (v: string) => void)(e.target.value)}
                style={{ width: "100%", boxSizing: "border-box", marginTop: 10, padding: "10px 12px", borderRadius: 8, border: `1px solid ${C.line}`, font: "500 12.5px Pretendard" }} />
            ))}
            {error && <div style={{ marginTop: 10, font: "500 12px Pretendard", color: C.fading }}>{error}</div>}
          </>
        )}
        <div style={{ display: "flex", gap: 8, marginTop: 14, justifyContent: "flex-end" }}>
          {!done && <Btn tone="primary" onClick={submit}>변경</Btn>}
          <Btn onClick={onClose}>닫기</Btn>
        </div>
      </Card>
    </div>
  );
}
