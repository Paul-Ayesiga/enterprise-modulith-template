import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { COOKIES, OIDC, cookieOpts, redirectUri, safePath, tokenUrl } from "../../../lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const url = new URL(req.url);
  const back = new URL("/", OIDC.portalUrl);

  const err = url.searchParams.get("error");
  if (err) {
    back.searchParams.set("error", url.searchParams.get("error_description") ?? err);
    return NextResponse.redirect(back.toString());
  }

  const code = url.searchParams.get("code");
  const state = url.searchParams.get("state");
  const jar = cookies();
  const expectedState = jar.get(COOKIES.state)?.value;
  const verifier = jar.get(COOKIES.verifier)?.value;

  if (!code || !state || !verifier || state !== expectedState) {
    back.searchParams.set("error", "Login could not be verified — please try again.");
    return NextResponse.redirect(back.toString());
  }

  const tokenRes = await fetch(tokenUrl(), {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code",
      code,
      redirect_uri: redirectUri(),
      client_id: OIDC.clientId,
      code_verifier: verifier
    }),
    cache: "no-store"
  });
  if (!tokenRes.ok) {
    back.searchParams.set("error", `Token exchange failed (${tokenRes.status}).`);
    return NextResponse.redirect(back.toString());
  }
  const tokens = (await tokenRes.json()) as {
    access_token: string;
    refresh_token?: string;
    id_token?: string;
    expires_in?: number;
  };

  const dest = safePath(jar.get(COOKIES.from)?.value);
  const res = NextResponse.redirect(new URL(dest, OIDC.portalUrl).toString());
  res.cookies.set(COOKIES.session, tokens.access_token, cookieOpts(tokens.expires_in ?? 300));
  if (tokens.refresh_token) {
    res.cookies.set(COOKIES.refresh, tokens.refresh_token, cookieOpts(28800));
  }
  if (tokens.id_token) {
    res.cookies.set(COOKIES.idToken, tokens.id_token, cookieOpts(3600));
  }
  res.cookies.set(COOKIES.verifier, "", cookieOpts(0));
  res.cookies.set(COOKIES.state, "", cookieOpts(0));
  res.cookies.set(COOKIES.from, "", cookieOpts(0));
  return res;
}
