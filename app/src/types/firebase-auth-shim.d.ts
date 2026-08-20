// @firebase/auth의 exports map은 "types" 조건이 "react-native" 조건보다 먼저 와서,
// tsc가 항상 플랫폼 공용 auth-public.d.ts를 골라 RN 전용 getReactNativePersistence를 놓친다
// (firebase-js-sdk 패키징 이슈, Metro 런타임 번들링에는 영향 없음 — react-native 조건으로 정상 로드됨).
// 여기서 타입만 보강한다.
import type { Persistence } from "@firebase/auth";

declare module "@firebase/auth" {
  export function getReactNativePersistence(storage: unknown): Persistence;
}
