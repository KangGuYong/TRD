import React, { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useSearchTrends } from "../api/hooks";
import { Card, H1, Muted, Screen, StageChip } from "../components/ui";
import { C, STAGE_COLOR } from "../theme";
import type { HomeNav } from "../navigation/types";

export default function SearchScreen() {
  const nav = useNavigation<HomeNav>();
  const [q, setQ] = useState("");
  const search = useSearchTrends(q);
  const hit = search.data?.items?.[0];

  return (
    <Screen>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingTop: 8 }}>
      <H1>이거 아직 써도 돼?</H1>
      <Muted style={{ marginTop: 4, marginBottom: 16 }}>단어를 입력하면 유효기간을 판정해드려요.</Muted>

      <View style={s.searchBox}>
        <Text style={{ color: C.faint }}>🔍</Text>
        <TextInput
          value={q}
          onChangeText={setQ}
          placeholder="예: 겉바속촉런"
          placeholderTextColor="rgba(20,19,15,0.35)"
          style={s.input}
          autoCorrect={false}
        />
      </View>

      {q.trim().length > 0 && search.isFetching && <Muted style={{ marginTop: 16 }}>찾는 중…</Muted>}

      {hit && (
        <Pressable onPress={() => nav.navigate("Detail", { id: hit.id })}>
          <Card style={{ marginTop: 18 }}>
            <StageChip stage={hit.stage} label={hit.stageLabel} />
            <Text style={[s.verdict, { color: STAGE_COLOR[hit.stage] }]}>{hit.lifeText ?? hit.stageLabel}</Text>
            <Text style={s.word}>{hit.word}</Text>
            {!!hit.meaning && <Muted style={{ marginTop: 5 }}>{hit.meaning}</Muted>}
            <Text style={s.more}>근거 자세히 보기 →</Text>
          </Card>
        </Pressable>
      )}

      {q.trim().length > 0 && !search.isFetching && !hit && !search.isError && (
        <View style={s.empty}>
          <Text style={s.emptyTitle}>아직 데이터가 없어요</Text>
          <Muted style={{ marginTop: 6, textAlign: "center" }}>커뮤니티에서 잡히면 워치로 알려드릴게요.</Muted>
        </View>
      )}
    </ScrollView>
    </Screen>
  );
}

const s = StyleSheet.create({
  searchBox: { flexDirection: "row", alignItems: "center", gap: 9, backgroundColor: C.card, borderWidth: 1, borderColor: "rgba(20,19,15,0.1)", borderRadius: 14, paddingHorizontal: 15, paddingVertical: 12 },
  input: { flex: 1, fontSize: 15.5, color: C.ink, padding: 0 },
  verdict: { fontSize: 22, fontWeight: "700", letterSpacing: -0.4, marginTop: 12 },
  word: { fontSize: 17, fontWeight: "600", color: C.ink, marginTop: 12 },
  more: { marginTop: 16, fontWeight: "600", fontSize: 13.5, color: C.ink },
  empty: { marginTop: 18, padding: 26, borderWidth: 1, borderStyle: "dashed", borderColor: "rgba(20,19,15,0.16)", borderRadius: 19, alignItems: "center" },
  emptyTitle: { fontSize: 15.5, fontWeight: "600", color: C.ink },
});
