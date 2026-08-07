import React from "react";
import { ScrollView, StyleSheet, Text, View } from "react-native";
import { useMeSummary, useMyGrade, useMyLedger } from "../api/hooks";
import { Card, H1, Muted, StateView } from "../components/ui";
import { C } from "../theme";

export default function MeScreen() {
  const grade = useMyGrade();
  const ledger = useMyLedger();
  const summary = useMeSummary();

  return (
    <ScrollView style={{ flex: 1, backgroundColor: C.bg }} contentContainerStyle={{ padding: 20, paddingTop: 8, paddingBottom: 40 }}>
      <H1>나</H1>

      {/* 등급 */}
      <View style={{ marginTop: 16 }}>
        <StateView query={grade}>
          {(g) => (
            <Card>
              <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start" }}>
                <View style={s.gradePill}>
                  <View style={[s.dot, { backgroundColor: C.rising }]} />
                  <Text style={s.gradeCode}>{g.grade}</Text>
                  <Text style={s.gradeName}>{g.gradeName}</Text>
                </View>
                <View style={{ alignItems: "flex-end" }}>
                  <Muted style={{ fontSize: 11 }}>판정 완료</Muted>
                  <Text style={s.judged}>{g.judgedCount}</Text>
                </View>
              </View>

              <View style={s.divider} />
              <Muted style={{ fontSize: 12, marginBottom: 13 }}>다음 등급까지 — {g.nextGrade}</Muted>
              <View style={{ gap: 13 }}>
                {g.requirements.map((r, i) => {
                  const pct = Math.min(100, (r.current / r.required) * 100);
                  const col = r.met ? C.rising : C.ink;
                  return (
                    <View key={i}>
                      <View style={{ flexDirection: "row", justifyContent: "space-between" }}>
                        <Text style={s.reqLabel}>{r.label}</Text>
                        <Text style={[s.reqFig, { color: col }]}>{r.current} / {r.required}</Text>
                      </View>
                      <View style={s.track}><View style={{ width: `${pct}%`, height: "100%", borderRadius: 9, backgroundColor: col }} /></View>
                      <Muted style={{ fontSize: 11.5, marginTop: 6 }}>{r.basis}</Muted>
                    </View>
                  );
                })}
              </View>
              {!!g.note && <Muted style={s.note}>{g.note}</Muted>}
            </Card>
          )}
        </StateView>
      </View>

      {/* 통계 */}
      {summary.data && (
        <View style={{ flexDirection: "row", gap: 9, marginTop: 11 }}>
          <Stat label="예측 적중률" value={`${Math.round(summary.data.voteHitRate * 100)}%`} sub={`투표 ${summary.data.votesTotal}회 중 ${summary.data.votesCorrect}회`} />
          <Stat label="읽은 트렌드" value={`${summary.data.totalRead}`} sub="이번 달" />
        </View>
      )}

      {/* 원장 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={ledger} empty={(d) => d.items.length === 0}>
          {(l) => (
            <Card>
              <Text style={s.ledgerTitle}>점수 원장</Text>
              <View style={{ gap: 12, marginTop: 14 }}>
                {l.items.map((e, i) => (
                  <View key={i} style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "flex-start", gap: 12 }}>
                    <View style={{ flex: 1 }}>
                      <Text style={s.lWord}>{e.word}</Text>
                      <Muted style={{ fontSize: 11.5, marginTop: 3 }}>{e.reason}</Muted>
                    </View>
                    <Text style={[s.lDelta, { color: e.delta > 0 ? C.rising : e.delta < 0 ? C.fading : C.faint }]}>
                      {e.delta > 0 ? `+${e.delta}` : e.delta}
                    </Text>
                  </View>
                ))}
              </View>
            </Card>
          )}
        </StateView>
      </View>
    </ScrollView>
  );
}

function Stat({ label, value, sub }: { label: string; value: string; sub: string }) {
  return (
    <Card style={{ flex: 1, padding: 17 }}>
      <Muted style={{ fontSize: 12 }}>{label}</Muted>
      <Text style={{ fontSize: 26, fontWeight: "700", color: C.ink, marginTop: 8 }}>{value}</Text>
      <Muted style={{ fontSize: 11.5, marginTop: 5 }}>{sub}</Muted>
    </Card>
  );
}

const s = StyleSheet.create({
  gradePill: { flexDirection: "row", alignItems: "center", gap: 7, paddingVertical: 5, paddingLeft: 8, paddingRight: 11, borderRadius: 100, backgroundColor: "rgba(27,158,82,0.11)" },
  dot: { width: 6, height: 6, borderRadius: 9 },
  gradeCode: { fontWeight: "700", color: C.rising, fontSize: 12 },
  gradeName: { fontWeight: "600", color: C.rising, fontSize: 12 },
  judged: { fontSize: 22, fontWeight: "700", color: C.ink, marginTop: 6 },
  divider: { height: 1, backgroundColor: "rgba(20,19,15,0.08)", marginVertical: 16 },
  reqLabel: { fontSize: 13, fontWeight: "600", color: C.ink },
  reqFig: { fontSize: 11.5, fontWeight: "500" },
  track: { height: 5, borderRadius: 9, backgroundColor: "rgba(20,19,15,0.08)", overflow: "hidden", marginTop: 6 },
  note: { fontSize: 12.5, marginTop: 16, padding: 13, borderRadius: 12, backgroundColor: "rgba(20,19,15,0.045)" },
  ledgerTitle: { fontSize: 13, fontWeight: "600", color: C.ink },
  lWord: { fontSize: 13.5, fontWeight: "600", color: C.ink },
  lDelta: { fontSize: 13.5, fontWeight: "700" },
});
