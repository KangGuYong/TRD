import * as Application from "expo-application";
import AsyncStorage from "@react-native-async-storage/async-storage";
import { Platform } from "react-native";

/**
 * 제보의 기기 ID(SP4 §4) — 서버가 HMAC 해시로만 저장한다. 안드로이드 ID, iOS 제조사별 ID,
 * 웹은 처음 실행할 때 만든 무작위 ID. 얻지 못하면 null(헤더 없이 보낸다 — 제보는 된다).
 */
const WEB_KEY = "trd.installId";
let cached: string | null | undefined;

export async function deviceId(): Promise<string | null> {
  if (cached !== undefined) return cached;
  try {
    if (Platform.OS === "android") {
      cached = Application.getAndroidId();
    } else if (Platform.OS === "ios") {
      cached = await Application.getIosIdForVendorAsync();
    } else {
      let id = await AsyncStorage.getItem(WEB_KEY);
      if (!id) {
        id = Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join("");
        await AsyncStorage.setItem(WEB_KEY, id);
      }
      cached = id;
    }
  } catch {
    cached = null;
  }
  return cached ?? null;
}
