import React from "react";
import { Text } from "react-native";
import { createBottomTabNavigator } from "@react-navigation/bottom-tabs";
import { createNativeStackNavigator } from "@react-navigation/native-stack";
import { C } from "../theme";
import type { HomeStackParamList } from "./types";
import HomeScreen from "../screens/HomeScreen";
import DetailScreen from "../screens/DetailScreen";
import SearchScreen from "../screens/SearchScreen";
import SubmitScreen from "../screens/SubmitScreen";
import WatchScreen from "../screens/WatchScreen";
import MeScreen from "../screens/MeScreen";

const Stack = createNativeStackNavigator<HomeStackParamList>();
const Tab = createBottomTabNavigator();

function HomeStack() {
  return (
    <Stack.Navigator screenOptions={{ headerShown: false, contentStyle: { backgroundColor: C.bg } }}>
      <Stack.Screen name="Home" component={HomeScreen} />
      <Stack.Screen name="Detail" component={DetailScreen} options={{ headerShown: true, title: "", headerBackTitle: "오늘의 5개", headerStyle: { backgroundColor: C.bg }, headerShadowVisible: false }} />
    </Stack.Navigator>
  );
}

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
      <Tab.Screen name="제보" component={SubmitScreen} options={{ tabBarIcon: icon("＋") }} />
      <Tab.Screen name="워치" component={WatchScreen} options={{ tabBarIcon: icon("◉") }} />
      <Tab.Screen name="나" component={MeScreen} options={{ tabBarIcon: icon("☺") }} />
    </Tab.Navigator>
  );
}
