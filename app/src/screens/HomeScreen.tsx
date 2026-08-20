import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useDailyTrends, useMeSummary } from "../api/hooks";
import type { TrendSummary } from "../api/types";
import { Card, H1, Muted, Screen, StageChip, StateView } from "../components/ui";
import { C, STAGE_COLOR, STAGE_TINT } from "../theme";
import { useReads } from "../state/reads";
import type { HomeNav } from "../navigation/types";

export default function HomeScreen() {
  const nav = useNavigation<HomeNav>();
  const trends = useDailyTrends();
  const summary = useMeSummary();
  const { read, markRead } = useReads();

  const open = (t: TrendSummary) => {
    markRead(t.id);
    nav.navigate("Detail", { id: t.id });
  };

  return (
    <Screen>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingTop: 8, paddingBottom: 40 }}>
      <View style={s.head}>
        <View>
          <Muted style={{ fontSize: 12.5 }}>오늘</Muted>
          <H1>오늘의 5개</H1>
        </View>
        <View style={s.streak}>
          <Text style={s.streakText}>🔥 {summary.data?.streakDays ?? "—"}</Text>
        </View>
      </View>

      <StateView query={trends} empty={(d) => d.items.length === 0}>
        {(d) => {
          const readCount = d.items.filter((t) => read.has(t.id)).length;
          const allRead = readCount >= d.items.length && d.items.length > 0;
          return (
            <>
              <View style={s.progress}>
                {d.items.map((t, i) => (
                  <View key={t.id} style={[s.pdot, { backgroundColor: read.has(t.id) ? C.ink : "rgba(20,19,15,0.13)" }]} />
                ))}
                <Muted style={{ marginLeft: 6, fontSize: 12 }}>{readCount}/{d.items.length} 읽음</Muted>
              </View>

              <View style={{ gap: 11, marginTop: 14 }}>
                {d.items.map((t) => (
                  <Pressable key={t.id} onPress={() => open(t)} style={({ pressed }) => [{ opacity: read.has(t.id) ? 0.62 : pressed ? 0.9 : 1 }]}>
                    <Card>
                      <View style={s.cardTop}>
                        <StageChip stage={t.stage} label={t.stageLabel} />
                        {!!t.dText && (
                          <Text style={[s.badge, { backgroundColor: STAGE_TINT[t.stage], color: STAGE_COLOR[t.stage] }]}>{t.dText}</Text>
                        )}
                      </View>
                      <View style={s.wordRow}>
                        <Text style={s.word}>{t.word}</Text>
                        {read.has(t.id) && <Text style={s.readMark}>읽음</Text>}
                      </View>
                      {!!t.meaning && <Text style={s.meaning}>{t.meaning}</Text>}
                      <View style={s.cardFoot}>
                        <Muted style={{ fontSize: 11.5 }}>{t.pathText || "아직 이동 없음"}</Muted>
                        {!!t.ageShort && <Muted style={{ fontSize: 11.5 }}>{t.ageShort}</Muted>}
                      </View>
                    </Card>
                  </Pressable>
                ))}
              </View>

              {allRead ? (
                <View style={s.done}>
                  <Text style={s.doneTitle}>오늘 5개 다 읽었어요</Text>
                  <Text style={s.doneSub}>내일 아침 새 5개가 도착합니다.</Text>
                </View>
              ) : (
                <Muted style={{ textAlign: "center", marginTop: 26 }}>여기까지가 오늘 전부예요</Muted>
              )}
            </>
          );
        }}
      </StateView>
    </ScrollView>
    </Screen>
  );
}

const s = StyleSheet.create({
  head: { flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", marginBottom: 16 },
  streak: { flexDirection: "row", alignItems: "center", backgroundColor: "rgba(20,19,15,0.06)", paddingHorizontal: 12, paddingVertical: 8, borderRadius: 100 },
  streakText: { fontWeight: "600", color: C.ink, fontSize: 13 },
  progress: { flexDirection: "row", alignItems: "center", gap: 4 },
  pdot: { width: 22, height: 3, borderRadius: 9 },
  cardTop: { flexDirection: "row", justifyContent: "space-between", alignItems: "center" },
  badge: { fontSize: 12.5, fontWeight: "700", paddingHorizontal: 9, paddingVertical: 6, borderRadius: 8, overflow: "hidden" },
  wordRow: { flexDirection: "row", alignItems: "baseline", gap: 8, marginTop: 13 },
  word: { fontSize: 22, fontWeight: "700", letterSpacing: -0.4, color: C.ink },
  readMark: { fontSize: 11.5, color: "rgba(20,19,15,0.35)" },
  meaning: { fontSize: 14.5, color: "rgba(20,19,15,0.62)", lineHeight: 24, marginTop: 6 },
  cardFoot: { flexDirection: "row", justifyContent: "space-between", marginTop: 15, paddingTop: 12, borderTopWidth: 1, borderTopColor: C.line },
  done: { backgroundColor: C.ink, borderRadius: 19, padding: 26, marginTop: 26, alignItems: "center" },
  doneTitle: { color: "#fff", fontWeight: "700", fontSize: 17 },
  doneSub: { color: "rgba(255,255,255,0.6)", fontSize: 13.5, marginTop: 7 },
});
