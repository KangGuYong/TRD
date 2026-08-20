import React, { useState } from "react";
import { Pressable, StyleSheet, Text, TextInput, View } from "react-native";
import { useAuth } from "../state/auth";
import { Screen } from "../components/ui";
import { C } from "../theme";

export default function SignInScreen() {
  const { signInWithEmail, signUpWithEmail, resetPassword } = useAuth();
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await fn();
    } catch (e) {
      setError(e instanceof Error ? e.message : "요청에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  const onEmailSubmit = () =>
    run(() => (mode === "signin" ? signInWithEmail(email, password) : signUpWithEmail(email, password)));

  const onForgotPassword = () =>
    run(async () => {
      if (!email.trim()) throw new Error("비밀번호를 재설정할 이메일을 먼저 입력하세요");
      await resetPassword(email);
      setNotice("재설정 메일을 보냈습니다. 받은편지함을 확인하세요.");
    });

  return (
    <Screen edges={["top", "bottom"]} style={s.wrap}>
      <View style={{ flex: 1, justifyContent: "center" }}>
        <View style={s.bars}>
          {([[C.seed, 16], [C.rising, 38], [C.peak, 70], [C.fading, 30]] as [string, number][]).map(([c, h], i) => (
            <View key={i} style={{ width: 26, height: h, borderRadius: 4, backgroundColor: c }} />
          ))}
        </View>
        <Text style={s.h2}>유행수명</Text>
        <Text style={s.p}>제보하려면 로그인이 필요합니다.{"\n"}둘러보기는 로그인 없이도 가능합니다.</Text>

        <View style={{ marginTop: 30, gap: 10 }}>
          <TextInput
            style={s.input}
            placeholder="이메일"
            placeholderTextColor="rgba(20,19,15,0.35)"
            autoCapitalize="none"
            keyboardType="email-address"
            value={email}
            onChangeText={setEmail}
          />
          <TextInput
            style={s.input}
            placeholder="비밀번호 (6자 이상)"
            placeholderTextColor="rgba(20,19,15,0.35)"
            secureTextEntry
            value={password}
            onChangeText={setPassword}
          />
        </View>

        {error && <Text style={s.err}>{error}</Text>}
        {notice && <Text style={s.notice}>{notice}</Text>}

        <Pressable onPress={onEmailSubmit} disabled={busy || !email || !password} style={[s.ctaOutline, (busy || !email || !password) && { opacity: 0.5 }]}>
          <Text style={s.ctaOutlineText}>{mode === "signin" ? "이메일로 로그인" : "이메일로 가입"}</Text>
        </Pressable>

        <View style={{ flexDirection: "row", justifyContent: "space-between", marginTop: 12 }}>
          <Pressable onPress={() => setMode(mode === "signin" ? "signup" : "signin")}>
            <Text style={s.link}>{mode === "signin" ? "계정이 없으신가요? 가입" : "이미 계정이 있으신가요? 로그인"}</Text>
          </Pressable>
          {mode === "signin" && (
            <Pressable onPress={onForgotPassword}>
              <Text style={s.link}>비밀번호 찾기</Text>
            </Pressable>
          )}
        </View>
      </View>

      {/* 구글 로그인: Expo Go에서 OAuth 리다이렉트를 처리할 방법이 없어 임시 비활성화.
          개발 빌드(dev client)로 전환하면 auth.tsx의 signInWithGoogle을 다시 연결할 것. */}
      <Text style={s.googleNotice}>구글 로그인은 준비 중입니다. 이메일로 이용해주세요.</Text>
    </Screen>
  );
}

const s = StyleSheet.create({
  wrap: { padding: 26, paddingTop: 20, paddingBottom: 20 },
  bars: { flexDirection: "row", gap: 7, alignItems: "flex-end", height: 74, marginBottom: 34 },
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  input: { borderWidth: 1, borderColor: "rgba(20,19,15,0.14)", borderRadius: 13, paddingHorizontal: 16, paddingVertical: 14, fontSize: 15, color: C.ink, backgroundColor: "#fff" },
  err: { fontSize: 13.5, color: C.fading, marginTop: 10, lineHeight: 21 },
  notice: { fontSize: 13.5, color: C.rising, marginTop: 10, lineHeight: 21 },
  link: { fontSize: 12.5, color: C.sub, fontWeight: "500" },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center", backgroundColor: C.ink },
  ctaText: { color: "#fff", fontWeight: "600", fontSize: 16 },
  ctaOutline: { marginTop: 16, paddingVertical: 15, borderRadius: 13, alignItems: "center", borderWidth: 1.5, borderColor: C.ink },
  ctaOutlineText: { color: C.ink, fontWeight: "600", fontSize: 15 },
  googleNotice: { textAlign: "center", fontSize: 12.5, color: C.sub },
});
