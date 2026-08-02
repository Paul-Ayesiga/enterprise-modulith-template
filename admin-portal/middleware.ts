import { NextResponse, type NextRequest } from "next/server";
import { COOKIES, cookieOpts, refreshTokens, tokenExp } from "./app/lib/oidc";

// Gate the whole admin portal behind login, and silently refresh the access token when it expires. Runs
// on the Edge runtime, so it uses only edge-safe helpers from ./app/lib/oidc (no node:crypto / Buffer).
export async function middleware(req: NextRequest) {
  const { pathname, search } = req.nextUrl;

  if (pathname.startsWith("/api/auth")) {
    return NextResponse.next();
  }

  const accessToken = req.cookies.get(COOKIES.session)?.value;
  const exp = accessToken ? tokenExp(accessToken) : null;
  if (exp != null && exp * 1000 > Date.now() + 5000) {
    return NextResponse.next();
  }

  const refreshToken = req.cookies.get(COOKIES.refresh)?.value;
  if (refreshToken) {
    const tokens = await refreshTokens(refreshToken);
    if (tokens?.access_token) {
      const res = req.method === "GET" ? NextResponse.redirect(req.url) : NextResponse.next();
      res.cookies.set(COOKIES.session, tokens.access_token, cookieOpts(tokens.expires_in ?? 300));
      if (tokens.refresh_token) {
        res.cookies.set(COOKIES.refresh, tokens.refresh_token, cookieOpts(28800));
      }
      return res;
    }
  }

  if (req.method !== "GET") {
    return NextResponse.next();
  }
  const login = req.nextUrl.clone();
  login.pathname = "/api/auth/login";
  login.search = "";
  login.searchParams.set("from", pathname + search);
  return NextResponse.redirect(login);
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|ico|txt|woff2?)).*)"]
};
