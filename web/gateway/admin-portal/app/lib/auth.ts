// Node-only auth helpers: PKCE (node:crypto) and the current session (reads the httpOnly cookie via
// next/headers). Shared, edge-safe config + decode live in ./oidc.
import crypto from "crypto";
import { cookies } from "next/headers";
import { COOKIES, decodeJwt } from "./oidc";

export { COOKIES, OIDC, authorizeUrl, cookieOpts, endSessionUrl, redirectUri, safePath, tokenUrl } from "./oidc";

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
