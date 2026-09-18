/** /admin API 클라이언트. 세션 쿠키는 브라우저가 자동 전송(credentials: include). */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** CSRF: 서버가 발급한 XSRF-TOKEN 쿠키를 쓰기 요청 헤더로 되돌려 보낸다(쿠키-헤더 이중 제출). */
const CSRF_COOKIE = "XSRF-TOKEN";
const CSRF_HEADER = "X-XSRF-TOKEN";

function readCookie(name: string): string | null {
  const match = document.cookie.split("; ").find((c) => c.startsWith(name + "="));
  return match ? decodeURIComponent(match.slice(name.length + 1)) : null;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (method !== "GET") {
    // 매 요청마다 쿠키를 새로 읽는다 — 로그인 직후 서버가 토큰을 교체해도 따로 처리할 필요가 없다.
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let detail = res.statusText;
    try {
      const p = await res.json();
      detail = p.detail ?? p.message ?? detail;
    } catch {
      /* non-json */
    }
    throw new ApiError(res.status, detail);
  }
  // void 컨트롤러 메서드는 200 + 빈 본문을 준다(204가 아님) — 상태코드로만 판단하면 JSON.parse가 깨진다.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(p: string) => request<T>("GET", p),
  post: <T>(p: string, b?: unknown) => request<T>("POST", p, b),
  put: <T>(p: string, b?: unknown) => request<T>("PUT", p, b),
};

/** SPA 부팅·로그인 직전에 호출 — XSRF-TOKEN 쿠키를 받아 둔다. */
export async function ensureCsrf(): Promise<void> {
  await request<void>("GET", "/admin/auth/csrf");
}

/** 시연용 픽스처 사용 여부. 기본은 실제 API — VITE_USE_FIXTURES=true 를 명시했을 때만 픽스처. */
export const USE_FIXTURES = (import.meta.env.VITE_USE_FIXTURES ?? "false") === "true";
