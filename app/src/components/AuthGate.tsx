import React from "react";
import { useAuth } from "../state/auth";
import SignInScreen from "../screens/SignInScreen";

/** 로그인이 필요한 탭(제보·나)을 감싼다. 둘러보기(홈·검색·워치)는 감싸지 않는다. */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { user, initializing } = useAuth();
  if (initializing) return null;
  return user ? <>{children}</> : <SignInScreen />;
}
