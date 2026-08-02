import { NextResponse } from "next/server";
import { COOKIES, OIDC, cookieOpts } from "../../../lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// Local logout — clear the portal session cookies. The Keycloak SSO session persists, so logging back
// in is a silent redirect. For a full single-sign-out, redirect to endSessionUrl() with the id_token
// hint and a post_logout_redirect_uri registered on the client.
export async function GET() {
  const res = NextResponse.redirect(new URL("/", OIDC.portalUrl).toString());
  res.cookies.set(COOKIES.session, "", cookieOpts(0));
  res.cookies.set(COOKIES.idToken, "", cookieOpts(0));
  return res;
}
