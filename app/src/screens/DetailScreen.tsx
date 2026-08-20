import React, { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useRoute, type RouteProp } from "@react-navigation/native";
import { useCreateReport, useTrendDetail, useToggleWatch, useVote } from "../api/hooks";
import type { ReportReason } from "../api/types";
import { Card, Muted, Screen, StageChip, StateView } from "../components/ui";
import { C, STAGE_COLOR } from "../theme";
import type { HomeStackParamList } from "../navigation/types";

export default function DetailScreen() {
  const { params } = useRoute<RouteProp<HomeStackParamList, "Detail">>();
  const detail = useTrendDetail(params.id);
  const vote = useVote(params.id);
  const toggleWatch = useToggleWatch();

  return (
    <Screen edges={["bottom"]}>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingBottom: 48 }}>
      <StateView query={detail}>
        {(d) => {
          const color = STAGE_COLOR[d.stage];
          return (
            <>
              <View style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                <StageChip stage={d.stage} label={d.stageLabel} />
                {!!d.dText && <Muted style={{ fontSize: 12.5 }}>{d.dText}</Muted>}
              </View>
              <Text style={s.word}>{d.word}</Text>
              {!!d.verdict && <Text style={[s.verdict, { color }]}>{d.verdict}</Text>}
              {!!d.verdictWhy && <Muted style={{ marginTop: 8, lineHeight: 24 }}>{d.verdictWhy}</Muted>}

              {/* 전파 경로 */}
              {!!d.propagationPath?.length && (
                <Card style={{ marginTop: 22 }}>
                  <Text style={s.sectionLabel}>전파 경로</Text>
                  <View style={{ gap: 10, marginTop: 12 }}>
                    {d.propagationPath.map((p, i) => (
                      <View key={i} style={s.pathRow}>
                        <View style={[s.pathDot, { backgroundColor: p.reached ? color : "rgba(20,19,15,0.16)" }]} />
                        <View style={{ flex: 1 }}>
                          <Text style={[s.pathCh, { color: p.reached ? C.ink : "rgba(20,19,15,0.32)" }]}>{p.channel}</Text>
                          {!!p.note && <Muted style={{ fontSize: 12 }}>{p.note}</Muted>}
                        </View>
                        <Muted style={{ fontSize: 11 }}>{p.date ?? "—"}</Muted>
                      </View>
                    ))}
                  </View>
                </Card>
              )}

              {/* 연령대 */}
              {!!d.ages?.length && (
                <View style={{ marginTop: 22 }}>
                  <Text style={s.sectionLabel}>연령대 인지도</Text>
                  {!!d.ageSentence && <Text style={s.ageSentence}>{d.ageSentence}</Text>}
                  <View style={{ gap: 9, marginTop: 12 }}>
                    {d.ages.map((a, i) => (
                      <View key={i} style={s.ageRow}>
                        <Text style={s.ageLabel}>{a.label}</Text>
                        <View style={s.ageTrack}>
                          <View style={{ width: `${Math.max(2, a.pct)}%`, height: "100%", borderRadius: 9, backgroundColor: color }} />
                        </View>
                        <Text style={s.agePct}>{a.pct}%</Text>
                      </View>
                    ))}
                  </View>
                </View>
              )}

              {/* 뜻/유래/예문 */}
              {!!d.meaning && <Field label="뜻" value={d.meaning} />}
              {!!d.origin && <Field label="유래" value={d.origin} />}
              {!!d.example && <Field label="예문" value={d.example} boxed />}

              {/* 투표 */}
              <Card style={{ marginTop: 22 }}>
                <Text style={s.voteTitle}>이거 더 뜰까요?</Text>
                {!!d.voteCount && <Muted style={{ fontSize: 12.5, marginTop: 6 }}>{d.voteCount}</Muted>}
                <View style={{ flexDirection: "row", gap: 8, marginTop: 14 }}>
                  <VoteBtn label="뜬다" onPress={() => vote.mutate(true)} busy={vote.isPending} />
                  <VoteBtn label="안 뜬다" onPress={() => vote.mutate(false)} busy={vote.isPending} />
                </View>
              </Card>

              {/* 워치 */}
              <Pressable
                onPress={() => toggleWatch.mutate({ keyword: d.word, on: !d.watched, trendId: params.id })}
                disabled={toggleWatch.isPending}
                style={[s.watchBtn, d.watched ? s.watchOff : s.watchOn, toggleWatch.isPending && { opacity: 0.5 }]}
              >
                <Text style={[s.watchText, { color: d.watched ? C.ink : "#fff" }]}>
                  {d.watched ? "워치에서 빼기" : "워치에 추가하고 알림 받기"}
                </Text>
              </Pressable>
              {toggleWatch.isError && (
                <Text style={s.watchError}>
                  {toggleWatch.error instanceof Error ? toggleWatch.error.message : "요청에 실패했습니다"}
                </Text>
              )}

              {/* 신고 */}
              <ReportSection trendId={params.id} />
            </>
          );
        }}
      </StateView>
    </ScrollView>
    </Screen>
  );
}

function Field({ label, value, boxed }: { label: string; value: string; boxed?: boolean }) {
  return (
    <View style={{ marginTop: 22 }}>
      <Text style={s.sectionLabel}>{label}</Text>
      <Text style={[s.fieldValue, boxed && s.fieldBoxed]}>{value}</Text>
    </View>
  );
}
function VoteBtn({ label, onPress, busy }: { label: string; onPress: () => void; busy: boolean }) {
  return (
    <Pressable onPress={onPress} disabled={busy} style={s.voteBtn}>
      <Text style={s.voteBtnText}>{label}</Text>
    </Pressable>
  );
}

const REPORT_REASONS: { key: ReportReason; label: string }[] = [
  { key: "DEFAMATION", label: "명예훼손" },
  { key: "BUSINESS_INTERFERENCE", label: "영업방해" },
  { key: "OTHER", label: "기타" },
];

function ReportSection({ trendId }: { trendId: string }) {
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState<ReportReason | null>(null);
  const [detail, setDetail] = useState("");
  const report = useCreateReport();

  if (report.isSuccess) {
    return (
      <View style={{ marginTop: 22 }}>
        <Muted style={{ color: C.rising }}>신고가 접수됐습니다. 검토 후 처리됩니다.</Muted>
      </View>
    );
  }

  const submit = () => {
    if (!reason) return;
    report.mutate({ trendItemId: trendId, reason, detail: detail.trim() || undefined });
  };

  return (
    <View style={{ marginTop: 22 }}>
      <Pressable onPress={() => setOpen((o) => !o)}>
        <Text style={s.reportToggle}>{open ? "신고 취소" : "신고하기"}</Text>
      </Pressable>
      {open && (
        <Card style={{ marginTop: 10 }}>
          <Text style={s.sectionLabel}>신고 사유</Text>
          <View style={{ flexDirection: "row", gap: 6, marginTop: 8, flexWrap: "wrap" }}>
            {REPORT_REASONS.map((r) => {
              const on = reason === r.key;
              return (
                <Pressable key={r.key} onPress={() => setReason(r.key)} style={[s.reasonChip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
                  <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 12.5 }}>{r.label}</Text>
                </Pressable>
              );
            })}
          </View>
          <TextInput value={detail} onChangeText={setDetail} placeholder="상세 내용 (선택)" placeholderTextColor="rgba(20,19,15,0.35)"
            style={[s.reportInput, { marginTop: 10 }]} multiline />
          {report.isError && <Muted style={{ color: C.fading, marginTop: 8 }}>{report.error instanceof Error ? report.error.message : "신고 접수에 실패했습니다"}</Muted>}
          <Pressable onPress={submit} disabled={!reason || report.isPending}
            style={[s.reportSubmit, { backgroundColor: reason ? C.fading : "rgba(20,19,15,0.1)" }]}>
            <Text style={{ color: reason ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 13.5 }}>
              {report.isPending ? "접수 중…" : "신고 접수"}
            </Text>
          </Pressable>
        </Card>
      )}
    </View>
  );
}

const s = StyleSheet.create({
  word: { fontSize: 36, fontWeight: "700", letterSpacing: -0.9, color: C.ink, marginTop: 12 },
  verdict: { fontSize: 17, fontWeight: "600", marginTop: 12 },
  sectionLabel: { fontSize: 12, fontWeight: "600", color: "rgba(20,19,15,0.4)" },
  pathRow: { flexDirection: "row", alignItems: "center", gap: 12 },
  pathDot: { width: 9, height: 9, borderRadius: 9 },
  pathCh: { fontSize: 14, fontWeight: "600" },
  ageSentence: { fontSize: 15.5, fontWeight: "600", color: C.ink, marginTop: 10 },
  ageRow: { flexDirection: "row", alignItems: "center", gap: 10 },
  ageLabel: { width: 40, fontSize: 12.5, color: C.sub },
  ageTrack: { flex: 1, height: 6, borderRadius: 9, backgroundColor: "rgba(20,19,15,0.07)", overflow: "hidden" },
  agePct: { width: 34, textAlign: "right", fontSize: 12, color: C.faint },
  fieldValue: { fontSize: 15, color: C.ink, lineHeight: 26, marginTop: 8 },
  fieldBoxed: { padding: 14, backgroundColor: "rgba(20,19,15,0.045)", borderRadius: 13 },
  voteTitle: { fontSize: 14.5, fontWeight: "600", color: C.ink },
  voteBtn: { flex: 1, paddingVertical: 14, borderRadius: 12, alignItems: "center", backgroundColor: "rgba(20,19,15,0.05)" },
  voteBtnText: { fontWeight: "600", color: C.ink },
  watchBtn: { marginTop: 22, paddingVertical: 16, borderRadius: 14, alignItems: "center" },
  watchOn: { backgroundColor: C.ink },
  watchOff: { backgroundColor: "transparent", borderWidth: 1, borderColor: "rgba(20,19,15,0.16)" },
  watchText: { fontWeight: "600", fontSize: 15 },
  watchError: { marginTop: 10, fontSize: 13, color: C.fading, textAlign: "center", lineHeight: 20 },
  reportToggle: { fontSize: 13, fontWeight: "600", color: C.sub },
  reasonChip: { paddingHorizontal: 12, paddingVertical: 8, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  reportInput: { backgroundColor: "#fff", borderWidth: 1, borderColor: "rgba(20,19,15,0.1)", borderRadius: 12, paddingHorizontal: 13, paddingVertical: 12, fontSize: 14, color: C.ink, minHeight: 60, textAlignVertical: "top" },
  reportSubmit: { marginTop: 12, paddingVertical: 13, borderRadius: 12, alignItems: "center" },
});
