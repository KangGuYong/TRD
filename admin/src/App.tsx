import React, { useState } from "react";
import { Layout, type ScreenId } from "./components/Layout";
import { RoleProvider } from "./state/role";
import TodayScreen from "./screens/TodayScreen";
import MergeQueueScreen from "./screens/MergeQueueScreen";
import ParamStudioScreen from "./screens/ParamStudioScreen";
import UserLedgerScreen from "./screens/UserLedgerScreen";
import AuditLogScreen from "./screens/AuditLogScreen";
import { C } from "./theme";

const TITLE: Record<ScreenId, string> = {
  "ADM-010": "오늘의 작업",
  "ADM-100": "병합 검수",
  "ADM-600": "파라미터 스튜디오",
  "ADM-311": "유저 상세 · 원장",
  "ADM-700": "감사 로그",
  stub: "준비 중",
};

export default function App() {
  const [screen, setScreen] = useState<ScreenId>("ADM-010");

  return (
    <RoleProvider>
      <Layout screen={screen} setScreen={setScreen} title={TITLE[screen]}>
        {screen === "ADM-010" && <TodayScreen goMerge={() => setScreen("ADM-100")} />}
        {screen === "ADM-100" && <MergeQueueScreen />}
        {screen === "ADM-600" && <ParamStudioScreen />}
        {screen === "ADM-311" && <UserLedgerScreen />}
        {screen === "ADM-700" && <AuditLogScreen />}
        {screen === "stub" && (
          <div style={{ maxWidth: 700, padding: 40, textAlign: "center", color: C.faint, font: "500 14px Pretendard" }}>
            이 화면은 준비 중입니다. P0(ADM-010·100·600·311·700) 다음 순서로 구현됩니다 — 05 §E.
          </div>
        )}
      </Layout>
    </RoleProvider>
  );
}
