import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { COOKIES, OIDC, cookieOpts, endSessionUrl } from "../../../lib/auth";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

// End the Keycloak SSO session (so re-login needs credentials, not a silent redirect) and clear the
// portal cookies. post_logout_redirect_uri returns the user here; Keycloak needs it registered on the
// client (see the realm export) — if it isn't, Keycloak shows its own logged-out page, which is fine.
export async function GET() {
  const idToken = cookies().get(COOKIES.idToken)?.value;

  const url = new URL(endSessionUrl());
  url.searchParams.set("post_logout_redirect_uri", `${OIDC.portalUrl}/`);
  url.searchParams.set("client_id", OIDC.clientId);
  if (idToken) {
    url.searchParams.set("id_token_hint", idToken);
  }

  const res = NextResponse.redirect(url.toString());
  res.cookies.set(COOKIES.session, "", cookieOpts(0));
  res.cookies.set(COOKIES.refresh, "", cookieOpts(0));
  res.cookies.set(COOKIES.idToken, "", cookieOpts(0));
  return res;
}
