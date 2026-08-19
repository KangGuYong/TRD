import React from "react";
import { Pressable, StyleSheet, Text, View } from "react-native";
import type { Category } from "../api/types";
import { C } from "../theme";

export const CATS: { key: Category; label: string }[] = [
  { key: "MEME", label: "밈·신조어" }, { key: "PRODUCT", label: "상품" }, { key: "PERSON_CHANNEL", label: "인물·채널" },
  { key: "CHALLENGE", label: "챌린지" }, { key: "SLANG", label: "슬랭" }, { key: "ETC", label: "기타" },
];
export const TIMES: { h: number; label: string; sub: string }[] = [
  { h: 7, label: "아침 7시", sub: "출근길 전에" }, { h: 8, label: "아침 8시", sub: "출근길에 딱" },
  { h: 12, label: "점심 12시", sub: "밥 먹으면서" }, { h: 22, label: "밤 10시", sub: "자기 전에" },
];

export function PreferencesForm({
  cats, onToggleCat, hour, onSelectHour, section = "both",
}: {
  cats: Category[];
  onToggleCat: (k: Category) => void;
  hour: number;
  onSelectHour: (h: number) => void;
  section?: "categories" | "time" | "both";
}) {
  return (
    <View>
      {section !== "time" && (
        <View>
          <Text style={s.h2}>관심 분야 3개만{"\n"}골라주세요</Text>
          <Text style={s.p}>{cats.length}/3 선택</Text>
          <View style={s.chipWrap}>
            {CATS.map((c) => {
              const on = cats.includes(c.key);
              return (
                <Pressable key={c.key} onPress={() => onToggleCat(c.key)} style={[s.chip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
                  <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 14.5 }}>{c.label}</Text>
                </Pressable>
              );
            })}
          </View>
        </View>
      )}

      {section !== "categories" && (
        <View style={section === "both" ? { marginTop: 30 } : undefined}>
          <Text style={s.h2}>몇 시에 알려드릴까요</Text>
          <View style={{ gap: 9, marginTop: 16 }}>
            {TIMES.map((t) => {
              const on = hour === t.h;
              return (
                <Pressable key={t.h} onPress={() => onSelectHour(t.h)} style={[s.timeRow, { borderColor: on ? C.ink : "rgba(20,19,15,0.1)", borderWidth: 1.5 }]}>
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
  );
}

const s = StyleSheet.create({
  h2: { fontSize: 30, fontWeight: "700", letterSpacing: -0.7, color: C.ink, lineHeight: 40 },
  p: { fontSize: 15, color: C.sub, lineHeight: 25, marginTop: 14 },
  chipWrap: { flexDirection: "row", flexWrap: "wrap", gap: 8, marginTop: 22 },
  chip: { paddingHorizontal: 16, paddingVertical: 13, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  timeRow: { flexDirection: "row", justifyContent: "space-between", alignItems: "center", padding: 17, borderRadius: 15, backgroundColor: "#fff" },
  timeLabel: { fontSize: 16, fontWeight: "600", color: C.ink },
  timeSub: { fontSize: 12.5, color: C.sub, marginTop: 3 },
  radio: { width: 20, height: 20, borderRadius: 20 },
});
