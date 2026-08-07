import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type HomeStackParamList = {
  Home: undefined;
  Detail: { id: string };
};
export type HomeNav = NativeStackNavigationProp<HomeStackParamList>;
