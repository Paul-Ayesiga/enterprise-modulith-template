import { NextResponse } from "next/server";
import { COOKIES, OIDC, authorizeUrl, cookieOpts, pkce, randomState, redirectUri } from "../../../lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const from = new URL(req.url).searchParams.get("from") ?? "/";
  const { verifier, challenge } = pkce();
  const state = randomState();

  const url = new URL(authorizeUrl());
  url.searchParams.set("client_id", OIDC.clientId);
  url.searchParams.set("redirect_uri", redirectUri());
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", OIDC.scope);
  url.searchParams.set("state", state);
  url.searchParams.set("code_challenge", challenge);
  url.searchParams.set("code_challenge_method", "S256");

  const res = NextResponse.redirect(url.toString());
  res.cookies.set(COOKIES.verifier, verifier, cookieOpts(600));
  res.cookies.set(COOKIES.state, state, cookieOpts(600));
  res.cookies.set(COOKIES.from, from, cookieOpts(600));
  return res;
}
