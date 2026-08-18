import React, { useState } from "react";
import { Layout, type ScreenId } from "./components/Layout";
import { RoleProvider } from "./state/role";
import { AuthProvider, useAuth } from "./state/auth";
import TodayScreen from "./screens/TodayScreen";
import MergeQueueScreen from "./screens/MergeQueueScreen";
import VerdictScreen from "./screens/VerdictScreen";
import SeedScreen from "./screens/SeedScreen";
import ParamStudioScreen from "./screens/ParamStudioScreen";
import UserLedgerScreen from "./screens/UserLedgerScreen";
import AuditLogScreen from "./screens/AuditLogScreen";
import AdminAccountsScreen from "./screens/AdminAccountsScreen";
import LoginScreen from "./screens/LoginScreen";
import TrendListScreen from "./screens/TrendListScreen";
import TrendDetailScreen from "./screens/TrendDetailScreen";
import { C } from "./theme";

const TITLE: Record<ScreenId, string> = {
  "ADM-010": "오늘의 작업",
  "ADM-100": "병합 검수",
  "ADM-110": "트렌드 항목 목록",
  "ADM-111": "트렌드 항목 상세",
  "ADM-200": "판정 관리",
  "ADM-500": "시딩 관리",
  "ADM-600": "파라미터 스튜디오",
  "ADM-311": "유저 상세 · 원장",
  "ADM-700": "감사 로그",
  "ADM-800": "관리자 계정",
  stub: "준비 중",
};

export default function App() {
  return (
    <AuthProvider>
      <AuthGate />
    </AuthProvider>
  );
}

function AuthGate() {
  const { state } = useAuth();

  if (state.status === "loading") return null;
  if (state.status === "unauthenticated") return <LoginScreen />;

  // fixture 모드: principal 없음 → 기존 데모 역할 전환 그대로. 실 로그인 모드: 서버가 준 역할로 고정.
  const principal = state.principal;
  return (
    <RoleProvider initialRole={principal?.role} locked={!!principal}>
      <Console />
    </RoleProvider>
  );
}

function Console() {
  const [screen, setScreen] = useState<ScreenId>("ADM-010");
  const [selectedTrendItemId, setSelectedTrendItemId] = useState<string | null>(null);

  return (
    <Layout screen={screen} setScreen={setScreen} title={TITLE[screen]}>
      {screen === "ADM-010" && <TodayScreen goMerge={() => setScreen("ADM-100")} />}
      {screen === "ADM-100" && <MergeQueueScreen />}
      {screen === "ADM-110" && <TrendListScreen onSelect={(id) => { setSelectedTrendItemId(id); setScreen("ADM-111"); }} />}
      {screen === "ADM-111" && <TrendDetailScreen trendItemId={selectedTrendItemId} onBack={() => setScreen("ADM-110")} />}
      {screen === "ADM-200" && <VerdictScreen />}
      {screen === "ADM-500" && <SeedScreen />}
      {screen === "ADM-600" && <ParamStudioScreen />}
      {screen === "ADM-311" && <UserLedgerScreen />}
      {screen === "ADM-700" && <AuditLogScreen />}
      {screen === "ADM-800" && <AdminAccountsScreen />}
      {screen === "stub" && (
        <div style={{ maxWidth: 700, padding: 40, textAlign: "center", color: C.faint, font: "500 14px Pretendard" }}>
          이 화면은 준비 중입니다. P0(ADM-010·100·600·311·700) 다음 순서로 구현됩니다 — 05 §E.
        </div>
      )}
    </Layout>
  );
}
