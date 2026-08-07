import React from "react";
import { ActivityIndicator, StyleSheet, Text, View } from "react-native";
import { C, STAGE_COLOR, STAGE_LABEL, STAGE_TINT, type Stage } from "../theme";

export function StageChip({ stage, label }: { stage: Stage; label?: string }) {
  const color = STAGE_COLOR[stage];
  return (
    <View style={[s.chip, { backgroundColor: STAGE_TINT[stage] }]}>
      <View style={[s.dot, { backgroundColor: color }]} />
      <Text style={[s.chipText, { color }]}>{label ?? STAGE_LABEL[stage]}</Text>
    </View>
  );
}

export function Card({ children, style }: { children: React.ReactNode; style?: any }) {
  return <View style={[s.card, style]}>{children}</View>;
}

export function H1({ children }: { children: React.ReactNode }) {
  return <Text style={s.h1}>{children}</Text>;
}
export function Muted({ children, style }: { children: React.ReactNode; style?: any }) {
  return <Text style={[s.muted, style]}>{children}</Text>;
}

/** 로딩/에러/빈 상태를 한 곳에서. data가 있으면 children(data)를 렌더. */
export function StateView<T>({
  query,
  empty,
  children,
}: {
  query: { isLoading: boolean; isError: boolean; error?: unknown; data?: T };
  empty?: (d: T) => boolean;
  children: (d: T) => React.ReactNode;
}) {
  if (query.isLoading) {
    return (
      <View style={s.center}>
        <ActivityIndicator color={C.ink} />
      </View>
    );
  }
  if (query.isError || query.data === undefined) {
    const msg = query.error instanceof Error ? query.error.message : "불러오지 못했어요";
    return (
      <View style={s.center}>
        <Text style={s.errTitle}>연결에 실패했어요</Text>
        <Muted style={{ marginTop: 6, textAlign: "center" }}>{msg}</Muted>
      </View>
    );
  }
  if (empty && empty(query.data)) {
    return (
      <View style={s.center}>
        <Muted>아직 없어요</Muted>
      </View>
    );
  }
  return <>{children(query.data)}</>;
}

const s = StyleSheet.create({
  chip: { flexDirection: "row", alignItems: "center", gap: 6, paddingVertical: 5, paddingLeft: 8, paddingRight: 10, borderRadius: 100, alignSelf: "flex-start" },
  dot: { width: 6, height: 6, borderRadius: 9 },
  chipText: { fontSize: 12, fontWeight: "600" },
  card: { backgroundColor: C.card, borderRadius: 19, padding: 18, borderWidth: 1, borderColor: C.line },
  h1: { fontSize: 30, fontWeight: "700", letterSpacing: -0.6, color: C.ink },
  muted: { fontSize: 13.5, color: C.sub, lineHeight: 20 },
  center: { flex: 1, alignItems: "center", justifyContent: "center", padding: 40 },
  errTitle: { fontSize: 15.5, fontWeight: "600", color: C.ink },
});
