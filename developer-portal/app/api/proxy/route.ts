// Server-side proxy for the "try it" console. The browser POSTs {method, path, token, body} here; this
// handler forwards it to the gateway front door and returns the response. Running it server-side means no
// CORS to negotiate and the bearer token never has to live in browser-reachable JS beyond the one request.
import { NextResponse } from "next/server";
import { apiBaseUrl } from "../../lib/gateway";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type ProxyRequest = {
  method?: string;
  path?: string;
  token?: string;
  body?: string | null;
  contentType?: string;
};

const ALLOWED = new Set(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD"]);

export async function POST(req: Request) {
  let payload: ProxyRequest;
  try {
    payload = (await req.json()) as ProxyRequest;
  } catch {
    return NextResponse.json({ error: "Request body must be JSON." }, { status: 400 });
  }

  const method = (payload.method ?? "GET").toUpperCase();
  const path = payload.path ?? "";

  if (!ALLOWED.has(method)) {
    return NextResponse.json({ error: `Method ${method} is not allowed.` }, { status: 400 });
  }
  // Only gateway-relative paths — appended to the fixed front-door URL, so this can't be aimed elsewhere.
  if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//")) {
    return NextResponse.json({ error: "Path must start with '/', e.g. /api/v1/settings." }, { status: 400 });
  }

  const url = `${apiBaseUrl()}${path}`;
  const headers: Record<string, string> = { accept: "application/json" };
  if (payload.token) {
    headers.authorization = `Bearer ${payload.token.trim()}`;
  }
  const init: RequestInit = { method, headers, cache: "no-store" };
  if (payload.body != null && payload.body !== "" && method !== "GET" && method !== "HEAD") {
    headers["content-type"] = payload.contentType || "application/json";
    init.body = payload.body;
  }

  const started = Date.now();
  try {
    const res = await fetch(url, init);
    const timeMs = Date.now() - started;
    const text = await res.text();
    const respHeaders: Record<string, string> = {};
    res.headers.forEach((value, key) => {
      respHeaders[key] = value;
    });
    return NextResponse.json({
      url,
      status: res.status,
      statusText: res.statusText,
      timeMs,
      headers: respHeaders,
      body: text
    });
  } catch (e) {
    return NextResponse.json(
      { url, error: e instanceof Error ? e.message : String(e) },
      { status: 502 }
    );
  }
}
