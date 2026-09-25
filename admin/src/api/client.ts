/** /admin API 클라이언트. 세션 쿠키는 브라우저가 자동 전송(credentials: include). */
export class ApiError extends Error {
  /** type: 서버 오류 본문의 type(about:blank면 undefined) — 화면이 사유별로 안내할 때 쓴다. */
  constructor(public status: number, message: string, public type?: string) {
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

/** 이미 JSON 문자열인 본문 — 다시 stringify하지 않는다(사례 파일은 바이트 그대로 보내야 같은 파일이 같은 해시가 된다). */
class RawJson {
  constructor(public text: string) {}
}

async function request<T>(method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>): Promise<T> {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...extraHeaders };
  if (method !== "GET") {
    // 매 요청마다 쿠키를 새로 읽는다 — 로그인 직후 서버가 토큰을 교체해도 따로 처리할 필요가 없다.
    const token = readCookie(CSRF_COOKIE);
    if (token) headers[CSRF_HEADER] = token;
  }
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers,
    body: body === undefined ? undefined : body instanceof RawJson ? body.text : JSON.stringify(body),
  });
  if (!res.ok) {
    let detail = res.statusText;
    let type: string | undefined;
    try {
      const p = await res.json();
      detail = p.detail ?? p.message ?? detail;
      type = p.type && p.type !== "about:blank" ? p.type : undefined;
    } catch {
      /* non-json */
    }
    if (res.status === 401 && path !== "/admin/auth/login") {
      // 세션 재검증(SP3 K7)이 세션을 끊었거나 만료 — 로그인 화면으로
      window.dispatchEvent(new CustomEvent("admin:unauthorized", { detail }));
    }
    throw new ApiError(res.status, detail, type);
  }
  // void 컨트롤러 메서드는 200 + 빈 본문을 준다(204가 아님) — 상태코드로만 판단하면 JSON.parse가 깨진다.
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export const api = {
  get: <T>(p: string) => request<T>("GET", p),
  post: <T>(p: string, b?: unknown, h?: Record<string, string>) => request<T>("POST", p, b, h),
  put: <T>(p: string, b?: unknown) => request<T>("PUT", p, b),
  postRaw: <T>(p: string, rawJson: string) => request<T>("POST", p, new RawJson(rawJson)),
};

/** SPA 부팅·로그인 직전에 호출 — XSRF-TOKEN 쿠키를 받아 둔다. */
export async function ensureCsrf(): Promise<void> {
  await request<void>("GET", "/admin/auth/csrf");
}

/** 시연용 픽스처 사용 여부. 기본은 실제 API — VITE_USE_FIXTURES=true 를 명시했을 때만 픽스처. */
export const USE_FIXTURES = (import.meta.env.VITE_USE_FIXTURES ?? "false") === "true";
