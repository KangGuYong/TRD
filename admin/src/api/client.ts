/** /admin API 클라이언트. 세션 쿠키는 브라우저가 자동 전송(credentials: include). */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "include",
    headers: { "Content-Type": "application/json" },
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
};

/** dev 시연용 픽스처 사용 여부. 백엔드 /admin 미구현 동안 기본 on. VITE_USE_FIXTURES=false 로 실 API 전환. */
export const USE_FIXTURES = (import.meta.env.VITE_USE_FIXTURES ?? "true") !== "false";
