/**
 * 근거 링크 → 플랫폼 라벨 미리보기. 서버 PlatformResolver(SP4 §3)의 복사본 — 서버 판별이 권위이고,
 * 어긋나면 미리보기 문구만 틀린다. 목록을 바꾸면 서버·앱(app/src/platform.ts)과 함께 바꾼다.
 */
const TABLE: [string, string[]][] = [
  ["디시", ["dcinside.com"]], ["더쿠", ["theqoo.net"]], ["에펨코리아", ["fmkorea.com"]], ["인스티즈", ["instiz.net"]],
  ["X", ["x.com", "twitter.com"]], ["인스타", ["instagram.com"]], ["스레드", ["threads.net", "threads.com"]],
  ["유튜브", ["youtube.com", "youtu.be"]], ["틱톡", ["tiktok.com"]], ["네이버", ["naver.com"]],
];

export function hostOf(url: string): string | null {
  const s = url.trim();
  const sep = s.indexOf("://");
  if (sep <= 0) return null;
  const scheme = s.slice(0, sep).toLowerCase();
  if (scheme !== "http" && scheme !== "https") return null;
  let rest = s.slice(sep + 3);
  for (const c of ["/", "?", "#", "\\"]) {
    const i = rest.indexOf(c);
    if (i >= 0) rest = rest.slice(0, i);
  }
  const at = rest.lastIndexOf("@");
  if (at >= 0) rest = rest.slice(at + 1);
  const colon = rest.indexOf(":");
  if (colon >= 0) rest = rest.slice(0, colon);
  let host = rest.toLowerCase();
  while (host.endsWith(".")) host = host.slice(0, -1);
  return host || null;
}

export function platformLabel(url: string): string {
  const host = hostOf(url);
  if (!host) return "기타";
  for (const [label, domains] of TABLE) {
    if (domains.some((d) => host === d || host.endsWith("." + d))) return label;
  }
  return "기타";
}
