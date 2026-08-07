import React from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NavigationContainer } from "@react-navigation/native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { View, Text, StyleSheet } from "react-native";

/**
 * 앱 셸. 하단 탭 5개(홈/검색/제보/워치/나) — 화면 구현은
 * 05-screen-endpoint-map.md §A / §E 순서로 채운다.
 * 색은 단계(씨앗/급상승/정점/식는중)에만 쓰고 나머지는 무채색(앱 설계 원칙 04).
 */
const C = { seed: "#9A968C", rising: "#1B9E52", peak: "#DFA400", fading: "#D8483C", ink: "#14130F", bg: "#F4F2ED" };
const Tab = createBottomTabNavigator();
const qc = new QueryClient();

function Placeholder({ title }: { title: string }) {
  return (
    <View style={styles.center}>
      <Text style={styles.h}>{title}</Text>
      <Text style={styles.sub}>화면 구현 예정 · API는 openapi.yaml에서 타입 생성</Text>
    </View>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={qc}>
      <NavigationContainer>
        <Tab.Navigator screenOptions={{ headerShown: false, tabBarActiveTintColor: C.ink }}>
          <Tab.Screen name="홈">{() => <Placeholder title="오늘의 5개" />}</Tab.Screen>
          <Tab.Screen name="검색">{() => <Placeholder title="이거 아직 써도 돼?" />}</Tab.Screen>
          <Tab.Screen name="제보">{() => <Placeholder title="제보" />}</Tab.Screen>
          <Tab.Screen name="워치">{() => <Placeholder title="워치" />}</Tab.Screen>
          <Tab.Screen name="나">{() => <Placeholder title="나 · 등급 · 원장" />}</Tab.Screen>
        </Tab.Navigator>
      </NavigationContainer>
    </QueryClientProvider>
  );
}

const styles = StyleSheet.create({
  center: { flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.bg, padding: 24 },
  h: { fontSize: 26, fontWeight: "700", color: C.ink },
  sub: { marginTop: 10, fontSize: 13, color: "rgba(20,19,15,0.5)", textAlign: "center" },
});
