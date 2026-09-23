import React, { createContext, useContext, useEffect, useMemo, useState } from "react";
import { api, ApiError, ensureCsrf, USE_FIXTURES } from "../api/client";
import type { Role } from "../theme";

export type AdminPrincipal = { id: string; loginId: string; displayName: string; role: Role };

type AuthState =
  | { status: "loading" }
  | { status: "unauthenticated"; error?: string }
  | { status: "authenticated"; principal: AdminPrincipal | null };

type AuthContextValue = {
  state: AuthState;
  login: (loginId: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

/**
 * fixture 모드(VITE_USE_FIXTURES=true일 때만)에서는 백엔드 없이 화면을 볼 수 있어야 하므로
 * 로그인을 생략하고 곧바로 인증됨으로 취급한다(principal은 null — 역할은 기존 RoleProvider 데모 전환이 담당).
 * 실제 모드에서는 세션 쿠키 유무를 /admin/me로 확인한다.
 */
export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>(USE_FIXTURES ? { status: "authenticated", principal: null } : { status: "loading" });

  useEffect(() => {
    if (USE_FIXTURES) return;
    ensureCsrf()
      .catch(() => {})
      .then(() => api.get<AdminPrincipal>("/admin/me"))
      .then((principal) => setState({ status: "authenticated", principal }))
      .catch(() => setState({ status: "unauthenticated" }));
  }, []);

  useEffect(() => {
    const onUnauthorized = (e: Event) =>
      setState({ status: "unauthenticated", error: (e as CustomEvent<string>).detail });
    window.addEventListener("admin:unauthorized", onUnauthorized);
    return () => window.removeEventListener("admin:unauthorized", onUnauthorized);
  }, []);

  const login = async (loginId: string, password: string) => {
    try {
      await ensureCsrf();
      const principal = await api.post<AdminPrincipal>("/admin/auth/login", { loginId, password });
      setState({ status: "authenticated", principal });
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : "로그인에 실패했습니다";
      setState({ status: "unauthenticated", error: msg });
      throw e;
    }
  };

  const logout = async () => {
    if (USE_FIXTURES) return;
    await api.post("/admin/auth/logout").catch(() => {});
    setState({ status: "unauthenticated" });
  };

  const value = useMemo(() => ({ state, login, logout }), [state]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 안에서만 사용할 수 있습니다");
  return ctx;
}
