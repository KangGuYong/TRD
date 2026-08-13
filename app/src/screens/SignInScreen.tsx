import React, { useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { useAuth } from "../state/auth";
import { C } from "../theme";

export default function SignInScreen() {
  const { signInWithGoogle } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onPress = async () => {
    setBusy(true);
    setError(null);
    try {
      await signInWithGoogle();
    } catch (e) {
      setError(e instanceof Error ? e.message : "로그인에 실패했습니다");
    } finally {
      setBusy(false);
    }
  };

  return (
    <View style={s.wrap}>
      <View style={{ flex: 1, justifyContent: "center" }}>
        <View style={s.bars}>
          {([[C.seed, 16], [C.rising, 38], [C.peak, 70], [C.fading, 30]] as [string, number][]).map(([c, h], i) => (
            <View key={i} style={{ width: 26, height: h, borderRadius: 4, backgroundColor: c }} />
          ))}
        </View>
        <Text style={s.h2}>유행수명</Text>
        <Text style={s.p}>제보하려면 로그인이 필요합니다.{"\n"}둘러보기는 로그인 없이도 가능합니다.</Text>
      </View>

      {error && <Text style={s.err}>{error}</Text>}

      <Pressable onPress={onPress} disabled={busy} style={[s.cta, busy && { opacity: 0.6 }]}>
        {busy ? <ActivityIndicator color="#fff" /> : <Text style={s.ctaText}>구글로 계속하기</Text>}
      </Pressable>
    </View>
  );
}

const s = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: C.bg, padding: 26, paddingTop: 70, paddingBottom: 44 },
  bars: { flexDirection: "row", gap: 7, alignItems: "flex-end", height: 74, marginBottom: 34 },
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  err: { fontSize: 13.5, color: C.fading, marginBottom: 12, lineHeight: 21 },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center", backgroundColor: C.ink },
  ctaText: { color: "#fff", fontWeight: "600", fontSize: 16 },
});
