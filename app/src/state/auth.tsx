import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import {
  getAuth,
  onIdTokenChanged,
  signInWithCredential,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  signOut as fbSignOut,
  GoogleAuthProvider,
  type User,
} from "@react-native-firebase/auth";
import { GoogleSignin } from "@react-native-google-signin/google-signin";
import { setTokenProvider } from "../api/client";

type AuthState = {
  user: User | null;
  initializing: boolean;
  signInWithGoogle: () => Promise<void>;
  signInWithEmail: (email: string, password: string) => Promise<void>;
  signUpWithEmail: (email: string, password: string) => Promise<void>;
  resetPassword: (email: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const Ctx = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [initializing, setInitializing] = useState(true);

  // ID 토큰은 1시간마다 만료된다. client의 tokenProvider가 동기 함수라 매 요청에서 await 할 수 없어,
  // onIdTokenChanged가 밀어주는 최신 토큰을 캐시해 둔다 — SDK가 만료 전 자동 갱신한다.
  const tokenRef = useRef<string | null>(null);

  useEffect(() => {
    setTokenProvider(() => tokenRef.current);

    GoogleSignin.configure({
      webClientId: process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID,
    });

    return onIdTokenChanged(getAuth(), async (u: User | null) => {
      setUser(u);
      tokenRef.current = u ? await u.getIdToken() : null;
      setInitializing(false);
    });
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      initializing,
      signInWithGoogle: async () => {
        await GoogleSignin.hasPlayServices({ showPlayServicesUpdateDialog: true });
        const { data } = await GoogleSignin.signIn();
        if (!data?.idToken) throw new Error("구글 로그인이 취소되었습니다");
        await signInWithCredential(getAuth(), GoogleAuthProvider.credential(data.idToken));
      },
      signInWithEmail: async (email, password) => {
        await signInWithEmailAndPassword(getAuth(), email.trim(), password);
      },
      signUpWithEmail: async (email, password) => {
        await createUserWithEmailAndPassword(getAuth(), email.trim(), password);
      },
      resetPassword: async (email) => {
        await sendPasswordResetEmail(getAuth(), email.trim());
      },
      signOut: async () => {
        await GoogleSignin.signOut();
        await fbSignOut(getAuth());
      },
    }),
    [user, initializing],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useAuth must be used within AuthProvider");
  return v;
}
