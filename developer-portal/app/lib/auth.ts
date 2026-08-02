// Minimal OIDC (Authorization Code + PKCE) against Keycloak's public `smsone-web` client. The access
// token lives in an httpOnly cookie — never reachable from browser JS — and is read server-side only.
import crypto from "crypto";
import { cookies } from "next/headers";

export const OIDC = {
  issuer: process.env.KEYCLOAK_ISSUER ?? "http://localhost:28081/realms/smsone",
  clientId: process.env.KEYCLOAK_CLIENT_ID ?? "smsone-web",
  scope: process.env.KEYCLOAK_SCOPE ?? "openid profile email organization",
  portalUrl: process.env.PORTAL_URL ?? "http://localhost:3001"
};

export const redirectUri = () => `${OIDC.portalUrl}/api/auth/callback`;
export const authorizeUrl = () => `${OIDC.issuer}/protocol/openid-connect/auth`;
export const tokenUrl = () => `${OIDC.issuer}/protocol/openid-connect/token`;
export const endSessionUrl = () => `${OIDC.issuer}/protocol/openid-connect/logout`;

// Cookie names: session access token, id token (logout hint), and the transient PKCE/state pair.
export const COOKIES = { session: "sms_at", idToken: "sms_it", verifier: "sms_pkce", state: "sms_state" };

/** httpOnly cookie options; Secure only in production so it still works over http on localhost. */
export function cookieOpts(maxAge: number) {
  return {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax" as const,
    path: "/",
    maxAge
  };
}

export function pkce() {
  const verifier = crypto.randomBytes(32).toString("base64url");
  const challenge = crypto.createHash("sha256").update(verifier).digest().toString("base64url");
  return { verifier, challenge };
}

export function randomState() {
  return crypto.randomBytes(16).toString("base64url");
}

export type Session = { token: string; sub: string; name?: string; email?: string; expiresAt: number };

/** The current session from the httpOnly access-token cookie, or null if absent/expired. Server-side only. */
export function getSession(): Session | null {
  const token = cookies().get(COOKIES.session)?.value;
  if (!token) return null;
  const claims = decodeJwt(token);
  if (!claims || typeof claims.exp !== "number" || claims.exp * 1000 <= Date.now()) {
    return null;
  }
  return {
    token,
    sub: String(claims.sub ?? ""),
    name: typeof claims.name === "string" ? claims.name : undefined,
    email: typeof claims.email === "string" ? claims.email : undefined,
    expiresAt: claims.exp * 1000
  };
}

/** Decode a JWT payload for display only — the modulith verifies the signature on every API call. */
export function decodeJwt(token: string): Record<string, unknown> | null {
  try {
    const payload = token.split(".")[1];
    if (!payload) return null;
    return JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Record<string, unknown>;
  } catch {
    return null;
  }
}
