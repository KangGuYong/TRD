import React, { useState } from "react";
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View } from "react-native";
import { useMeSummary, useMySubmissions, useSubmit } from "../api/hooks";
import type { Category } from "../api/types";
import { Card, H1, Muted, Screen, StateView } from "../components/ui";
import { C, STAGE_COLOR } from "../theme";

const CATS: { key: Category; label: string }[] = [
  { key: "MEME", label: "밈" }, { key: "PRODUCT", label: "상품" }, { key: "PERSON_CHANNEL", label: "인물·채널" },
  { key: "CHALLENGE", label: "챌린지" }, { key: "SLANG", label: "슬랭" }, { key: "ETC", label: "기타" },
];
const PLATS = ["디시", "더쿠", "X", "인스타", "유튜브", "기타"];
const CONFS: (10 | 30 | 50)[] = [10, 30, 50];
const CONF_LABEL: Record<number, string> = { 10: "가볍게", 30: "꽤 확실", 50: "확신함" };

export default function SubmitScreen() {
  const [tab, setTab] = useState<"new" | "mine">("new");
  return (
    <Screen>
    <ScrollView style={{ flex: 1 }} contentContainerStyle={{ padding: 20, paddingTop: 8, paddingBottom: 48 }}>
      <H1>제보</H1>
      <Muted style={{ marginTop: 4 }}>올리는 건 사람, 채점은 기계가 합니다.</Muted>

      <View style={s.tabs}>
        <TabBtn label="새 제보" active={tab === "new"} onPress={() => setTab("new")} />
        <TabBtn label="내 제보" active={tab === "mine"} onPress={() => setTab("mine")} />
      </View>

      {tab === "new" ? <NewSubmission /> : <MySubmissions />}
    </ScrollView>
    </Screen>
  );
}

function NewSubmission() {
  const submit = useSubmit();
  const [name, setName] = useState("");
  const [cat, setCat] = useState<Category | null>(null);
  const [plat, setPlat] = useState<string | null>(null);
  const [url, setUrl] = useState("");
  const [conf, setConf] = useState<10 | 30 | 50>(30);
  const [disc, setDisc] = useState(false);
  const [oneLine, setOneLine] = useState("");

  const summary = useMeSummary();
  const remaining = summary.data ? Math.max(0, summary.data.quotaMax - summary.data.quotaUsed) : null;
  const exhausted = remaining === 0;

  const ready = name.trim() && cat && plat && url.trim().length > 3 && oneLine.trim() && !exhausted;

  const onSubmit = () => {
    if (!ready) return;
    submit.mutate(
      { name: name.trim(), category: cat!, platform: plat!, evidenceUrl: url.trim(), confidence: conf, disclosure: disc, oneLine: oneLine.trim() },
      { onSuccess: () => { setName(""); setCat(null); setPlat(null); setUrl(""); setConf(30); setDisc(false); setOneLine(""); } }
    );
  };

  return (
    <View style={{ gap: 18, marginTop: 4 }}>
      <Field label="항목명"><TextInput value={name} onChangeText={setName} placeholder="예: 새벽뿌수기" placeholderTextColor="rgba(20,19,15,0.35)" style={s.input} /></Field>

      <Field label="카테고리">
        <Chips items={CATS.map((c) => c.label)} selected={cat ? CATS.find((c) => c.key === cat)!.label : null} onPick={(l) => setCat(CATS.find((c) => c.label === l)!.key)} />
      </Field>

      <Field label="최초 목격 플랫폼">
        <Chips items={PLATS} selected={plat} onPick={setPlat} />
      </Field>

      <Field label="한 줄 설명"><TextInput value={oneLine} onChangeText={setOneLine} placeholder="뜻을 한 줄로" placeholderTextColor="rgba(20,19,15,0.35)" style={s.input} /></Field>

      <Field label="근거 URL"><TextInput value={url} onChangeText={setUrl} placeholder="https://" placeholderTextColor="rgba(20,19,15,0.35)" autoCapitalize="none" style={s.input} /></Field>

      <View>
        <Text style={s.fieldLabel}>확신도 · 걸수록 크게 벌고 크게 잃습니다</Text>
        <View style={{ flexDirection: "row", gap: 7, marginTop: 8 }}>
          {CONFS.map((v) => {
            const on = conf === v;
            return (
              <Pressable key={v} onPress={() => setConf(v)} style={[s.conf, on && s.confOn]}>
                <Text style={[s.confV, { color: on ? "#fff" : C.ink }]}>{v}</Text>
                <Text style={[s.confL, { color: on ? "rgba(255,255,255,0.6)" : C.faint }]}>{CONF_LABEL[v]}</Text>
              </Pressable>
            );
          })}
        </View>
        <Muted style={{ marginTop: 10, fontSize: 12.5 }}>적중하면 최대 +{Math.round(conf * 2.5)}, 빗나가면 −{conf / 2}.</Muted>
      </View>

      <Pressable onPress={() => setDisc(!disc)} style={s.disc}>
        <View style={[s.checkbox, disc && { backgroundColor: C.ink, borderColor: C.ink }]}>{disc && <Text style={{ color: "#fff", fontSize: 12 }}>✓</Text>}</View>
        <View style={{ flex: 1 }}>
          <Text style={s.discTitle}>이해관계 고지</Text>
          <Muted style={{ fontSize: 12.5, marginTop: 4 }}>본인·소속사·거래처와 관련이 있습니다. 미고지 적발 시 점수 전액 소멸 + 3단계 강등.</Muted>
        </View>
      </Pressable>

      {remaining !== null && (
        <Muted style={exhausted ? { color: C.fading } : undefined}>
          {exhausted ? "이번 주 제보권을 모두 썼어요 · 월요일 00:00에 다시 채워져요" : `이번 주 제보권 ${remaining}장 남음`}
        </Muted>
      )}
      {submit.isError && <Muted style={{ color: C.fading }}>{(submit.error as Error).message}</Muted>}
      {submit.isSuccess && <Muted style={{ color: C.rising }}>접수됐습니다. 14일 뒤 자동 판정됩니다.</Muted>}

      <Pressable onPress={onSubmit} disabled={!ready || submit.isPending} style={[s.submit, { backgroundColor: ready ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: ready ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 15.5 }}>
          {submit.isPending ? "제보 중…" : exhausted ? "이번 주 제보권 소진" : ready ? `제보하기 · 확신도 ${conf} 걸기` : "항목명 · 카테고리 · 플랫폼 · URL 필요"}
        </Text>
      </Pressable>
    </View>
  );
}

function MySubmissions() {
  const q = useMySubmissions();
  return (
    <View style={{ marginTop: 4, minHeight: 200 }}>
      <StateView query={q} empty={(d) => d.length === 0}>
        {(subs) => (
          <View style={{ gap: 10 }}>
            {subs.map((m) => (
              <Card key={m.id} style={{ padding: 17 }}>
                <View style={{ flexDirection: "row", justifyContent: "space-between", alignItems: "center" }}>
                  <Text style={[s.status, statusStyle(m.status)]}>{statusLabel(m.status)}</Text>
                  {m.delta != null && <Text style={{ fontWeight: "700" }}>{m.delta > 0 ? `+${m.delta}` : m.delta}</Text>}
                </View>
                <Text style={s.subWord}>{m.word}</Text>
                {!!m.note && <Muted style={{ fontSize: 13, marginTop: 5 }}>{m.note}</Muted>}
                <Muted style={{ fontSize: 11.5, marginTop: 10 }}>확신도 {m.confidence} · {m.createdAt?.slice(0, 10)}</Muted>
              </Card>
            ))}
          </View>
        )}
      </StateView>
    </View>
  );
}

function statusLabel(s: string) {
  return { PENDING: "관측 중", HIT: "적중", MISS: "빗나감", VOID: "판정 불가" }[s] ?? s;
}
function statusStyle(s: string) {
  const c = { PENDING: C.seed, HIT: C.rising, MISS: C.fading, VOID: C.seed }[s] ?? C.ink;
  return { color: c };
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (<View><Text style={styles_fieldLabel}>{label}</Text><View style={{ marginTop: 8 }}>{children}</View></View>);
}
const styles_fieldLabel = { fontSize: 12, fontWeight: "600" as const, color: "rgba(20,19,15,0.45)" };

function Chips({ items, selected, onPick }: { items: string[]; selected: string | null; onPick: (v: string) => void }) {
  return (
    <View style={{ flexDirection: "row", flexWrap: "wrap", gap: 6 }}>
      {items.map((it) => {
        const on = selected === it;
        return (
          <Pressable key={it} onPress={() => onPick(it)} style={[s.pchip, on && { backgroundColor: C.ink, borderColor: C.ink }]}>
            <Text style={{ color: on ? "#fff" : C.ink, fontWeight: "500", fontSize: 13.5 }}>{it}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}
function TabBtn({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={[s.tab, active && { backgroundColor: "#fff" }]}>
      <Text style={{ fontWeight: "600", fontSize: 13, color: active ? C.ink : C.faint }}>{label}</Text>
    </Pressable>
  );
}

const s = StyleSheet.create({
  tabs: { flexDirection: "row", gap: 4, marginTop: 20, marginBottom: 18, padding: 3, backgroundColor: "rgba(20,19,15,0.06)", borderRadius: 11 },
  tab: { flex: 1, paddingVertical: 9, borderRadius: 9, alignItems: "center" },
  fieldLabel: { fontSize: 12, fontWeight: "600", color: "rgba(20,19,15,0.45)" },
  input: { backgroundColor: C.card, borderWidth: 1, borderColor: "rgba(20,19,15,0.1)", borderRadius: 13, paddingHorizontal: 15, paddingVertical: 14, fontSize: 15.5, color: C.ink },
  pchip: { paddingHorizontal: 14, paddingVertical: 10, borderRadius: 100, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  conf: { flex: 1, paddingVertical: 14, borderRadius: 13, alignItems: "center", gap: 5, borderWidth: 1, borderColor: "rgba(20,19,15,0.12)", backgroundColor: "#fff" },
  confOn: { backgroundColor: C.ink, borderColor: C.ink },
  confV: { fontSize: 18, fontWeight: "700" },
  confL: { fontSize: 11, fontWeight: "500" },
  disc: { flexDirection: "row", gap: 11, padding: 15, borderRadius: 13, backgroundColor: "#fff", borderWidth: 1, borderColor: "rgba(20,19,15,0.09)" },
  checkbox: { width: 20, height: 20, borderRadius: 6, borderWidth: 1.5, borderColor: "rgba(20,19,15,0.2)", alignItems: "center", justifyContent: "center" },
  discTitle: { fontSize: 13.5, fontWeight: "600", color: C.ink },
  submit: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
  status: { fontWeight: "600", fontSize: 11.5 },
  subWord: { fontSize: 18, fontWeight: "700", color: C.ink, marginTop: 12 },
});
