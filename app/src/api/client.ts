/**
 * 경량 타입드 API 클라이언트. openapi.yaml 계약을 따른다.
 * 베이스 URL은 EXPO_PUBLIC_API_URL로 주입(안드로이드 에뮬레이터는 10.0.2.2).
 * Authorization 헤더에는 Firebase ID 토큰이 실린다 — AuthProvider가 setTokenProvider로 공급.
 */
const BASE_URL = process.env.EXPO_PUBLIC_API_URL ?? "http://localhost:8080";

let tokenProvider: () => string | null = () => null;
export function setTokenProvider(fn: () => string | null) {
  tokenProvider = fn;
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const token = tokenProvider();
  const res = await fetch(`${BASE_URL}${path}`, {
    method,
    headers: {
      // 본문이 없는 요청(DELETE 등)에는 Content-Type을 붙이지 않는다 — 규격에 맞지 않고 일부 스택이 거부한다.
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const p = await res.json();
      detail = p.detail ?? p.message ?? detail;
    } catch {
      /* non-json error */
    }
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body),
  put: <T>(path: string, body?: unknown) => request<T>("PUT", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
};

export { BASE_URL };
