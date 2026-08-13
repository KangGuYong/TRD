import React, { useState } from "react";
import { StatusBar } from "expo-status-bar";
import { SafeAreaProvider } from "react-native-safe-area-context";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NavigationContainer } from "@react-navigation/native";
import RootNavigator from "./src/navigation/RootNavigator";
import OnboardingScreen from "./src/screens/OnboardingScreen";
import { ReadsProvider } from "./src/state/reads";
import { AuthProvider } from "./src/state/auth";

const qc = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 30_000 } },
});

export default function App() {
  // 온보딩 완료 게이트(인메모리). 추후 SecureStore/서버 preferences 존재 여부로 대체.
  const [onboarded, setOnboarded] = useState(false);

  return (
    <SafeAreaProvider>
      <QueryClientProvider client={qc}>
        <AuthProvider>
          <ReadsProvider>
            <StatusBar style="dark" />
            {onboarded ? (
              <NavigationContainer>
                <RootNavigator />
              </NavigationContainer>
            ) : (
              <OnboardingScreen onDone={() => setOnboarded(true)} />
            )}
          </ReadsProvider>
        </AuthProvider>
      </QueryClientProvider>
    </SafeAreaProvider>
  );
}
