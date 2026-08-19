import React, { useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useSavePreferences } from "../api/hooks";
import type { Category } from "../api/types";
import { PreferencesForm } from "../components/PreferencesForm";
import { Screen } from "../components/ui";
import { C } from "../theme";

export default function OnboardingScreen({ onDone }: { onDone: () => void }) {
  const save = useSavePreferences();
  const [step, setStep] = useState(0);
  const [cats, setCats] = useState<Category[]>([]);
  const [hour, setHour] = useState(8);

  const toggleCat = (k: Category) =>
    setCats((prev) => (prev.includes(k) ? prev.filter((c) => c !== k) : prev.length < 3 ? [...prev, k] : prev));

  const canNext = step === 1 ? cats.length === 3 : true;
  const next = () => {
    if (step < 2) return setStep(step + 1);
    save.mutate({ categories: cats, notifyHour: hour }, { onSettled: onDone }); // 실패해도 진입(오프라인 관용)
  };

  return (
    <Screen edges={["top", "bottom"]} style={s.wrap}>
      <View style={s.steps}>
        {[0, 1, 2].map((i) => <View key={i} style={[s.stepBar, { backgroundColor: i <= step ? C.ink : "rgba(20,19,15,0.15)" }]} />)}
      </View>

      <View style={{ flex: 1, justifyContent: "center" }}>
        {step === 0 && (
          <View>
            <View style={s.bars}>
              {[[C.seed, 16], [C.rising, 38], [C.peak, 70], [C.fading, 30], ["rgba(20,19,15,0.12)", 10]].map(([c, h], i) => (
                <View key={i} style={{ width: 26, height: h as number, borderRadius: 4, backgroundColor: c as string }} />
              ))}
            </View>
            <Text style={s.h2}>유행은 뜨는 게 아니라{"\n"}지나가는 겁니다.</Text>
            <Text style={s.p}>매일 아침 5개. 각각이 지금 어느 단계이고 며칠 남았는지 알려드릴게요.</Text>
          </View>
        )}

        {step === 1 && <PreferencesForm section="categories" cats={cats} onToggleCat={toggleCat} hour={hour} onSelectHour={() => {}} />}

        {step === 2 && (
          <View>
            <PreferencesForm section="time" cats={cats} onToggleCat={() => {}} hour={hour} onSelectHour={setHour} />
            <Text style={s.subtitle}>정해진 시간에 오는 알림 하나가 이 앱의 전부입니다.</Text>
          </View>
        )}
      </View>

      <Pressable onPress={next} disabled={!canNext || save.isPending} style={[s.cta, { backgroundColor: canNext ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: canNext ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 16 }}>
          {step === 0 ? "시작하기" : step === 1 ? (cats.length < 3 ? `${3 - cats.length}개 더 골라주세요` : "다음") : "오늘의 5개 보기"}
        </Text>
      </Pressable>
    </Screen>
  );
}

const s = StyleSheet.create({
  wrap: { padding: 26, paddingTop: 20, paddingBottom: 20 },
  steps: { flexDirection: "row", gap: 5 },
  stepBar: { height: 3, width: 34, borderRadius: 9 },
  bars: { flexDirection: "row", gap: 7, alignItems: "flex-end", height: 74, marginBottom: 34 },
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  subtitle: { fontSize: 13, color: C.sub, marginTop: 20, textAlign: "center" },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
});
