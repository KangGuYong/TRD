import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from "react";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { getApps, initializeApp } from "firebase/app";
import {
  getAuth,
  initializeAuth,
  getReactNativePersistence,
  onIdTokenChanged,
  signInWithCredential,
  signInWithEmailAndPassword,
  createUserWithEmailAndPassword,
  sendPasswordResetEmail,
  signOut as fbSignOut,
  GoogleAuthProvider,
  type Auth,
  type User,
  // "firebase/auth"의 exports 맵에는 react-native 조건이 없어 getReactNativePersistence가 안 잡힌다.
  // @firebase/auth를 직접 import해야 Metro가 RN 전용 빌드(AsyncStorage 영속성 포함)로 해석한다.
  // (타입 보강은 src/types/firebase-auth-shim.d.ts 참고)
} from "@firebase/auth";
import * as AuthSession from "expo-auth-session";
import * as WebBrowser from "expo-web-browser";
import { setTokenProvider } from "../api/client";

WebBrowser.maybeCompleteAuthSession();

// expo-auth-session의 Google.useIdTokenAuthRequest(암시적 id_token 플로우)는 deprecated고,
// 구글이 공개 클라이언트의 암시적 플로우를 정책상 막아 "OAuth 2.0 policy" 400 에러가 난다.
// 인가 코드 + PKCE로 우회한다 — "웹" 타입 OAuth 클라이언트라 코드 교환에 client secret이 필요한데,
// 설치형 앱은 애초에 진짜 기밀을 지킬 수 없어 구글도 이 경우 관행적으로 허용한다.
const googleDiscovery = {
  authorizationEndpoint: "https://accounts.google.com/o/oauth2/v2/auth",
  tokenEndpoint: "https://oauth2.googleapis.com/token",
};

const firebaseConfig = {
  apiKey: process.env.EXPO_PUBLIC_FIREBASE_API_KEY,
  authDomain: process.env.EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN,
  projectId: process.env.EXPO_PUBLIC_FIREBASE_PROJECT_ID,
  storageBucket: process.env.EXPO_PUBLIC_FIREBASE_STORAGE_BUCKET,
  messagingSenderId: process.env.EXPO_PUBLIC_FIREBASE_MESSAGING_SENDER_ID,
  appId: process.env.EXPO_PUBLIC_FIREBASE_APP_ID,
};

const firebaseApp = getApps()[0] ?? initializeApp(firebaseConfig);

// Fast Refresh로 모듈이 재평가되면 initializeAuth가 "already initialized"로 던진다 — 그때는 기존 인스턴스를 재사용한다.
let auth: Auth;
try {
  auth = initializeAuth(firebaseApp, { persistence: getReactNativePersistence(AsyncStorage) });
} catch {
  auth = getAuth(firebaseApp);
}

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

  // Expo Go 안에서는 네이티브 GoogleSignin을 쓸 수 없어, expo-auth-session으로 브라우저 기반
  // OAuth 흐름을 태우고(Expo 프록시 리다이렉트) 인가 코드를 받아, PKCE로 토큰을 교환해
  // 그 안의 id_token을 Firebase 자격증명으로 넘긴다.
  const [request, , promptAsync] = AuthSession.useAuthRequest(
    {
      clientId: process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID!,
      scopes: ["openid", "profile", "email"],
      responseType: AuthSession.ResponseType.Code,
      usePKCE: true,
      redirectUri: AuthSession.makeRedirectUri(),
    },
    googleDiscovery,
  );

  useEffect(() => {
    setTokenProvider(() => tokenRef.current);

    return onIdTokenChanged(auth, async (u: User | null) => {
      // 토큰을 먼저 채워야 한다 — setUser가 먼저 리렌더되면 AuthGate가 화면을 그리면서
      // 첫 API 요청이 tokenRef가 비어있는 채로(Authorization 헤더 없이) 나가 401이 난다.
      tokenRef.current = u ? await u.getIdToken() : null;
      setUser(u);
      setInitializing(false);
    });
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      initializing,
      signInWithGoogle: async () => {
        if (!request) throw new Error("구글 로그인을 준비하는 중입니다. 잠시 후 다시 시도하세요");
        const result = await promptAsync();
        if (result.type !== "success") throw new Error("구글 로그인이 취소되었습니다");
        const tokens = await AuthSession.exchangeCodeAsync(
          {
            clientId: process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_ID!,
            clientSecret: process.env.EXPO_PUBLIC_GOOGLE_WEB_CLIENT_SECRET,
            code: result.params.code,
            redirectUri: request.redirectUri,
            extraParams: { code_verifier: request.codeVerifier! },
          },
          googleDiscovery,
        );
        if (!tokens.idToken) throw new Error("구글 로그인에 실패했습니다");
        await signInWithCredential(auth, GoogleAuthProvider.credential(tokens.idToken));
      },
      signInWithEmail: async (email, password) => {
        await signInWithEmailAndPassword(auth, email.trim(), password);
      },
      signUpWithEmail: async (email, password) => {
        await createUserWithEmailAndPassword(auth, email.trim(), password);
      },
      resetPassword: async (email) => {
        await sendPasswordResetEmail(auth, email.trim());
      },
      signOut: async () => {
        await fbSignOut(auth);
      },
    }),
    [user, initializing, request],
  );

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useAuth must be used within AuthProvider");
  return v;
}
