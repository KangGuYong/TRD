import React, { useEffect, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet, Text, View } from "react-native";
import { useNavigation } from "@react-navigation/native";
import { useMyPreferences, useSavePreferences } from "../api/hooks";
import type { Category } from "../api/types";
import { PreferencesForm } from "../components/PreferencesForm";
import { H1, Screen } from "../components/ui";
import type { MeNav } from "../navigation/types";
import { C } from "../theme";

export default function SettingsScreen() {
  const nav = useNavigation<MeNav>();
  const prefs = useMyPreferences();
  const save = useSavePreferences();
  const [cats, setCats] = useState<Category[]>([]);
  const [hour, setHour] = useState(8);

  useEffect(() => {
    if (prefs.data) {
      setCats(prefs.data.categories);
      setHour(prefs.data.notifyHour);
    }
  }, [prefs.data]);

  const toggleCat = (k: Category) =>
    setCats((prev) => (prev.includes(k) ? prev.filter((c) => c !== k) : prev.length < 3 ? [...prev, k] : prev));

  const onSave = () => {
    save.mutate({ categories: cats, notifyHour: hour }, { onSuccess: () => nav.goBack() });
  };

  if (prefs.isLoading) {
    return (
      <Screen edges={["top", "bottom"]} style={s.center}>
        <ActivityIndicator color={C.ink} />
      </Screen>
    );
  }

  return (
    <Screen edges={["top", "bottom"]} style={s.wrap}>
      <H1>설정</H1>
      <View style={{ marginTop: 20, flex: 1 }}>
        <PreferencesForm cats={cats} onToggleCat={toggleCat} hour={hour} onSelectHour={setHour} />
      </View>
      <Pressable onPress={onSave} disabled={cats.length !== 3 || save.isPending} style={[s.cta, { backgroundColor: cats.length === 3 ? C.ink : "rgba(20,19,15,0.1)" }]}>
        <Text style={{ color: cats.length === 3 ? "#fff" : "rgba(20,19,15,0.35)", fontWeight: "600", fontSize: 16 }}>
          {cats.length !== 3 ? `관심 분야 ${3 - cats.length}개 더 골라주세요` : "저장"}
        </Text>
      </Pressable>
    </Screen>
  );
}

const s = StyleSheet.create({
  wrap: { padding: 20, paddingTop: 8, paddingBottom: 20, flex: 1 },
  center: { flex: 1, alignItems: "center", justifyContent: "center" },
  cta: { paddingVertical: 17, borderRadius: 15, alignItems: "center" },
});
