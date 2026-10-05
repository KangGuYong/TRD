import type { NativeStackNavigationProp } from "@react-navigation/native-stack";

export type HomeStackParamList = {
  Home: undefined;
  Detail: { id: string };
};
export type HomeNav = NativeStackNavigationProp<HomeStackParamList>;

/** 검색 탭도 상세로 들어가야 해서 자체 스택을 둔다(APP-3). Detail 파라미터는 홈과 같다. */
export type SearchStackParamList = {
  Search: undefined;
  Detail: { id: string };
};
export type SearchNav = NativeStackNavigationProp<SearchStackParamList>;

export type MeStackParamList = {
  Me: undefined;
  Settings: undefined;
};
export type MeNav = NativeStackNavigationProp<MeStackParamList>;
