import React, { useState } from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import { useSavePreferences } from "../api/hooks";
import type { Category } from "../api/types";
import { C } from "../theme";

const CATS: { key: Category; label: string }[] = [
  { key: "MEME", label: "밈·신조어" }, { key: "PRODUCT", label: "상품" }, { key: "PERSON_CHANNEL", label: "인물·채널" },
  { key: "CHALLENGE", label: "챌린지" }, { key: "SLANG", label: "슬랭" }, { key: "ETC", label: "기타" },
];
const TIMES: { h: number; label: string; sub: string }[] = [
  { h: 7, label: "아침 7시", sub: "출근길 전에" }, { h: 8, label: "아침 8시", sub: "출근길에 딱" },
  { h: 12, label: "점심 12시", sub: "밥 먹으면서" }, { h: 22, label: "밤 10시", sub: "자기 전에" },
];

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
    <View style={s.wrap}>
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

        {step === 1 && (
          <View>
            <Text style={s.h2}>관심 분야 3개만{"\n"}골라주세요</Text>
            <Text style={s.p}>{cats.length}/3 선택</Text>
            <View style={s.chipWrap}>
              {CATS.map((c) => {
                const on = cats.includes(c.key);
                return (
                  <Pressable key={c.key} onPress={() => toggleCat(c.key)} style={[s.chip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
                    <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 14.5 }}>{c.label}</Text>
                  </Pressable>
                );
              })}
            </View>
          </View>
        )}

        {step === 2 && (
          <View>
            <Text style={s.h2}>몇 시에 알려드릴까요</Text>
            <Text style={s.p}>정해진 시간에 오는 알림 하나가 이 앱의 전부입니다.</Text>
            <View style={{ gap: 9, marginTop: 16 }}>
              {TIMES.map((t) => {
                const on = hour === t.h;
                return (
                  <Pressable key={t.h} onPress={() => setHour(t.h)} style={[s.timeRow, { borderColor: on ? C.ink : "rgba(20,19,15,0.1)", borderWidth: on ? 1.5 : 1.5 }]}>
                    <View>
                      <Text style={s.timeLabel}>{t.label}</Text>
                      <Text style={s.timeSub}>{t.sub}</Text>
                    </View>
                    <View style={[s.radio, { borderWidth: on ? 6 : 1.5, borderColor: on ? C.ink : "rgba(20,19,15,0.18)" }]} />
                  </Pressable>
                );
              })}
            </View>
          </View>
        )}
      </View>

      <Pressable onPress={next} disabled={!canNext || save.isPending} style={[s.cta, { backgroundColor: canNext ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: canNext ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 16 }}>
          {step === 0 ? "시작하기" : step === 1 ? (cats.length < 3 ? `${3 - cats.length}개 더 골라주세요` : "다음") : "오늘의 5개 보기"}
        </Text>
      </Pressable>
    </View>
  );
}

const s = StyleSheet.create({
  wrap: { flex: 1, backgroundColor: C.bg, padding: 26, paddingTop: 70, paddingBottom: 44 },
  steps: { flexDirection: "row", gap: 5 },
  stepBar: { height: 3, width: 34, borderRadius: 9 },
  bars: { flexDirection: "row", gap: 7, alignItems: "flex-end", height: 74, marginBottom: 34 },
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  chipWrap: { flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 22 },
  chip: { paddingHorizontal: 16, paddingVertical: 13, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  timeRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", padding: 17, borderRadius: 15, backgroundColor: "#fff" },
  timeLabel: { fontSize: 16, fontWeight: "600", color: C.ink },
  timeSub: { fontSize: 12.5, color: C.sub, marginTop: 3 },
  radio: { width: 20, height: 20, borderRadius: 20 },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
});
