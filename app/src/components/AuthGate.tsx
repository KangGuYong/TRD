import React from "react";
import { useAuth } from "../state/auth";
import SignInScreen from "../screens/SignInScreen";

/** 로그인이 필요한 탭(제보·워치·나)을 감싼다. 워치는 유저별 개인 데이터라 로그인이 필수 — 둘러보기는 홈·검색만. */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, initializing } = useAuth();
  if (initializing) return null;
  return user ? <>{children}</> : <SignInScreen />;
}
