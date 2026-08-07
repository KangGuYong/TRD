import React from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { useWatch } from "../api/hooks";
import { H1, Muted, StageChip, StateView } from "../components/ui";
import { C } from "../theme";

export default function WatchScreen() {
  const q = useWatch();
  return (
    <ScrollView style={{ flex: 1, backgroundColor: C.bg }} contentContainerStyle={{ padding: 20, paddingTop: 8 }}>
      <H1>워치</H1>
      <Muted style={{ marginTop: 4, marginBottom: 20 }}>급상승 진입 시 즉시 알림</Muted>

      <View style={{ minHeight: 200 }}>
        <StateView query={q} empty={(d) => d.length === 0}>
          {(list) => (
            <View style={{ gap: 9 }}>
              {list.map((w) => (
                <View key={w.keyword} style={s.row}>
                  <View style={{ flex: 1 }}>
                    <Text style={s.word}>{w.keyword}</Text>
                    {!!w.note && <Muted style={{ fontSize: 12.5, marginTop: 4 }}>{w.note}</Muted>}
                  </View>
                  {w.stage && <StageChip stage={w.stage} />}
                </View>
              ))}
            </View>
          )}
        </StateView>
      </View>
    </ScrollView>
  );
}

const s = StyleSheet.create({
  row: { flexDirection: "row", alignItems: "center", gap: 12, backgroundColor: C.card, borderWidth: 1, borderColor: C.line, borderRadius: 16, padding: 16 },
  word: { fontSize: 15.5, fontWeight: "600", color: C.ink },
});
