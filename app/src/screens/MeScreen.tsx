import React from "react";
import { Pressable, ScrollView, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMeSummary, useMyGrade, useMyLedger, useMySubmissions, useWatch } from "../api/hooks";
import type { GradeRequirementKind } from "../api/types";
import { Card, H1, Muted, Screen, StateView } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { useAuth } from "../state/auth";
import { C } from "../theme";

const REQ_TEXT: Record<GradeRequirementKind, (current: number, required: number) => string> = {
  JUDGED_COUNT: (c, r) => `판정 완료된 제보 ${Math.round(c)}건 (목표 ${Math.round(r)}건)`,
  TRUST_INDEX: (c, r) => `예측 적중률 ${Math.round(c * 100)}% (목표 ${Math.round(r * 100)}%)`,
  ACTIVE_SCORE: (c, r) => `활동 점수 ${Math.round(c)}점 (목표 ${Math.round(r)}점)`,
};
const REQ_LABEL: Record<GradeRequirementKind, string> = {
  JUDGED_COUNT: "판정 완료된 제보",
  TRUST_INDEX: "예측 적중률",
  ACTIVE_SCORE: "활동 점수",
};

export default function MeScreen() {
  const nav = useNavigation<MeNav>();
  const { user, signOut } = useAuth();
  const grade = useMyGrade();
  const ledger = useMyLedger();
  const summary = useMeSummary();
  const watch = useWatch();
  const mySubs = useMySubmissions();

  return (
    <Screen>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingTop: 8, paddingBottom: 40 }}>
      <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
        <H1>나</H1>
        <Pressable onPress={() => signOut()} hitSlop={8}>
          <Text style={s.logout}>로그아웃</Text>
        </Pressable>
      </View>
      {!!user?.email && <Muted style={{ marginTop: 4 }}>{user.email}</Muted>}

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
                        <Text style={s.reqLabel}>{REQ_LABEL[r.kind]}</Text>
                        <Text style={[s.reqFig, { color: col }]}>{r.current} / {r.required}</Text>
                      </View>
                      <View style={s.track}><View style={{ width: `${pct}%`, height: "100%", borderRadius: 9, backgroundColor: col }} /></View>
                      <Muted style={{ fontSize: 11.5, marginTop: 6 }}>{REQ_TEXT[r.kind](r.current, r.required)}</Muted>
                    </View>
                  );
                })}
              </View>
              {!!g.note && <Muted style={s.note}>{g.note}</Muted>}
            </Card>
          )}
        </StateView>
      </View>

      {/* 스트릭 · 제보권 */}
      <View style={{ flexDirection: "row", gap: 9, marginTop: 11 }}>
        <Card style={{ flex: 1, padding: 15 }}>
          <Muted style={{ fontSize: 12 }}>🔥 스트릭</Muted>
          <Text style={s.devSoon}>개발 예정</Text>
        </Card>
        <Card style={{ flex: 1, padding: 15 }}>
          <Muted style={{ fontSize: 12 }}>이번 주 제보권</Muted>
          {summary.data ? (
            <Text style={s.statValue}>{summary.data.quotaUsed} / {summary.data.quotaMax}</Text>
          ) : (
            <Text style={s.devSoon}>불러오는 중</Text>
          )}
        </Card>
      </View>

      {/* 적중률 */}
      {summary.data && (
        <Card style={{ marginTop: 9, padding: 17 }}>
          <Muted style={{ fontSize: 12 }}>예측 적중률</Muted>
          <Text style={{ fontSize: 26, fontWeight: "700", color: C.ink, marginTop: 8 }}>
            {Math.round(summary.data.voteHitRate * 100)}%
          </Text>
          <Muted style={{ fontSize: 11.5, marginTop: 5 }}>
            투표 {summary.data.votesTotal}회 중 {summary.data.votesCorrect}회 적중
          </Muted>
        </Card>
      )}

      {/* 워치 요약 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={watch} empty={(d) => d.length === 0}>
          {(items) => (
            <Card>
              <Text style={s.sectionTitle}>워치 중인 키워드</Text>
              <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 7, marginTop: 12 }}>
                {items.slice(0, 5).map((w) => (
                  <View key={w.keyword} style={s.watchChip}>
                    <Text style={s.watchChipText}>{w.keyword}</Text>
                  </View>
                ))}
              </View>
              <Muted style={{ fontSize: 11.5, marginTop: 10 }}>{items.length}개 워치 중</Muted>
            </Card>
          )}
        </StateView>
      </View>

      {/* 내 제보 현황 */}
      <View style={{ marginTop: 11 }}>
        <StateView query={mySubs} empty={(d) => d.length === 0}>
          {(subs) => {
            const pending = subs.filter((s) => s.status === "PENDING").length;
            const hit = subs.filter((s) => s.status === "HIT").length;
            const miss = subs.filter((s) => s.status === "MISS").length;
            return (
              <Card>
                <Text style={s.sectionTitle}>내 제보 현황</Text>
                <View style={{ flexDirection: "row", gap: 18, marginTop: 12 }}>
                  <SubStat label="판정 대기" value={pending} color={C.ink} />
                  <SubStat label="적중" value={hit} color={C.rising} />
                  <SubStat label="실패" value={miss} color={C.fading} />
                </View>
              </Card>
            );
          }}
        </StateView>
      </View>

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

      <Pressable onPress={() => nav.navigate("Settings")} style={s.settingsRow}>
        <Text style={s.settingsText}>⚙ 설정 — 관심 분야 · 알림 시간</Text>
      </Pressable>
    </ScrollView>
    </Screen>
  );
}

function SubStat({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <View>
      <Text style={{ fontSize: 20, fontWeight: "700", color }}>{value}</Text>
      <Muted style={{ fontSize: 11, marginTop: 2 }}>{label}</Muted>
    </View>
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
  logout: { fontSize: 12.5, fontWeight: "600", color: C.sub },
  devSoon: { fontSize: 13, fontWeight: "600", color: C.faint, marginTop: 8 },
  statValue: { fontSize: 20, fontWeight: "700", color: C.ink, marginTop: 6 },
  sectionTitle: { fontSize: 13, fontWeight: "600", color: C.ink },
  watchChip: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 100, backgroundColor: "rgba(20,19,15,0.045)" },
  watchChipText: { fontSize: 12.5, color: C.ink, fontWeight: "500" },
  ledgerTitle: { fontSize: 13, fontWeight: "600", color: C.ink },
  lWord: { fontSize: 13.5, fontWeight: "600", color: C.ink },
  lDelta: { fontSize: 13.5, fontWeight: "700" },
  settingsRow: { marginTop: 18, alignItems: "center", padding: 10 },
  settingsText: { fontSize: 12.5, color: C.sub, fontWeight: "500" },
});
