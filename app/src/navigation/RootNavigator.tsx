import React from "react";
import { Text } from "react-native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { C } from "../theme";
import type { HomeStackParamList, MeStackParamList } from "./types";
import HomeScreen from "../screens/HomeScreen";
import DetailScreen from "../screens/DetailScreen";
import SearchScreen from "../screens/SearchScreen";
import SubmitScreen from "../screens/SubmitScreen";
import WatchScreen from "../screens/WatchScreen";
import MeScreen from "../screens/MeScreen";
import SettingsScreen from "../screens/SettingsScreen";
import { AuthGate } from "../components/AuthGate";

const GatedSubmit = () => <AuthGate><SubmitScreen /></AuthGate>;
const GatedWatch = () => <AuthGate><WatchScreen /></AuthGate>;

const Stack = createNativeStackNavigator<HomeStackParamList>();
const MeStackNav = createNativeStackNavigator<MeStackParamList>();
const Tab = createBottomTabNavigator();

function HomeStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
      <Stack.Screen name="Home" component={HomeScreen} />
      <Stack.Screen name="Detail" component={DetailScreen} options={{ headerShown: true, title: "", headerBackTitle: "오늘의 5개", headerStyle: { backgroundColor: C.bg }, headerShadowVisible: false }} />
    </Stack.Navigator>
  );
}

function MeStack() {
  return (
    <MeStackNav.Navigator screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
      <MeStackNav.Screen name="Me" component={MeScreen} />
      <MeStackNav.Screen name="Settings" component={SettingsScreen} options={{ headerShown: true, title: "설정", headerStyle: { backgroundColor: C.bg }, headerShadowVisible: false }} />
    </MeStackNav.Navigator>
  );
}

const GatedMe = () => <AuthGate><MeStack /></AuthGate>;

const icon = (glyph: string) => ({ color }: { color: string }) => <Text style={{ color, fontSize: 18 }}>{glyph}</Text>;

export default function RootNavigator() {
  return (
    <Tab.Navigator
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: C.ink,
        tabBarInactiveTintColor: "rgba(20,19,15,0.32)",
        tabBarStyle: { backgroundColor: C.bg, borderTopColor: C.line },
      }}
    >
      <Tab.Screen name="홈" component={HomeStack} options={{ tabBarIcon: icon("⌂") }} />
      <Tab.Screen name="검색" component={SearchScreen} options={{ tabBarIcon: icon("⌕") }} />
      <Tab.Screen name="제보" component={GatedSubmit} options={{ tabBarIcon: icon("＋") }} />
      <Tab.Screen name="워치" component={GatedWatch} options={{ tabBarIcon: icon("◉") }} />
      <Tab.Screen name="나" component={GatedMe} options={{ tabBarIcon: icon("☺") }} />
    </Tab.Navigator>
  );
}
