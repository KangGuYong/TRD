import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type HomeStackParamList = {
  Home: undefined;
  Detail: { id: string };
};
export type HomeNav = NativeStackNavigationProp<HomeStackParamList>;

export type MeStackParamList = {
  Me: undefined;
  Settings: undefined;
};
export type MeNav = NativeStackNavigationProp<MeStackParamList>;
