import React from "react";
import { ActivityIndicator, View } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NavigationContainer } from "@react-navigation/native";
import RootNavigator from "./src/navigation/RootNavigator";
import OnboardingScreen from "./src/screens/OnboardingScreen";
import { useMyPreferences } from "./src/api/hooks";
import { ReadsProvider } from "./src/state/reads";
import { AuthProvider, useAuth } from "./src/state/auth";
import { C } from "./src/theme";

const qc = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
});

/**
 * 온보딩 게이트. 비로그인(둘러보기)은 항상 통과. 로그인 상태면 서버에 저장된
 * preferences 존재 여부로 딱 1번만 온보딩을 보여준다 — 로컬 플래그가 아니라 서버가 진실.
 */
function Gate({ children }: { children: React.ReactNode }) {
  const { user, initializing } = useAuth();
  const prefs = useMyPreferences(!!user && !initializing);

  if (initializing) {
    return (
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.bg }}>
        <ActivityIndicator color={C.ink} />
      </View>
    );
  }
  if (!user) return <>{children}</>;
  if (prefs.isLoading) {
    return (
      <View style={{ flex: 1, alignItems: "center", justifyContent: "center", backgroundColor: C.bg }}>
        <ActivityIndicator color={C.ink} />
      </View>
    );
  }
  if (prefs.data === null) {
    return <OnboardingScreen onDone={() => prefs.refetch()} />;
  }
  return <>{children}</>;
}

export default function App() {
  return (
    <SafeAreaProvider>
      <QueryClientProvider client={qc}>
        <AuthProvider>
          <ReadsProvider>
            <StatusBar style="dark" />
            <Gate>
              <NavigationContainer>
                <RootNavigator />
              </NavigationContainer>
            </Gate>
          </ReadsProvider>
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}
