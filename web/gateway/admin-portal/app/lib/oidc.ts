// Edge-safe OIDC config + helpers. NO Node-only APIs (crypto/Buffer/next-headers) so this is importable
// from both the Node runtime (route handlers, RSC) and the Edge runtime (middleware).
export const OIDC = {
  issuer: process.env.KEYCLOAK_ISSUER ?? "http://localhost:28081/realms/smsone",
  clientId: process.env.KEYCLOAK_CLIENT_ID ?? "smsone-web",
  scope: process.env.KEYCLOAK_SCOPE ?? "openid profile email",
  portalUrl: process.env.PORTAL_URL ?? "http://localhost:3002"
};

export const redirectUri = () => `${OIDC.portalUrl}/api/auth/callback`;
export const authorizeUrl = () => `${OIDC.issuer}/protocol/openid-connect/auth`;
export const tokenUrl = () => `${OIDC.issuer}/protocol/openid-connect/token`;
export const endSessionUrl = () => `${OIDC.issuer}/protocol/openid-connect/logout`;

export const COOKIES = {
  session: "sms_at",
  refresh: "sms_rt",
  idToken: "sms_it",
  verifier: "sms_pkce",
  state: "sms_state",
  from: "sms_from"
};

export function cookieOpts(maxAge: number) {
  return { httpOnly: true, secure: process.env.NODE_ENV === "production", sameSite: "lax" as const, path: "/", maxAge };
}

/** Decode a JWT payload (base64url, UTF-8 safe) in edge or node. For display/exp only — never for authz. */
export function decodeJwt(token: string): Record<string, unknown> | null {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    const pad = (4 - (part.length % 4)) % 4;
    const b64 = part.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat(pad);
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    return JSON.parse(new TextDecoder().decode(bytes)) as Record<string, unknown>;
  } catch {
    return null;
  }
}

/** The token's `exp` (seconds), or null. */
export function tokenExp(token: string): number | null {
  const claims = decodeJwt(token);
  return claims && typeof claims.exp === "number" ? claims.exp : null;
}

export type TokenSet = { access_token: string; refresh_token?: string; id_token?: string; expires_in?: number };

/** Silent refresh with the refresh token (public client — no secret). Returns the new token set, or null. */
export async function refreshTokens(refreshToken: string): Promise<TokenSet | null> {
  try {
    const res = await fetch(tokenUrl(), {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        refresh_token: refreshToken,
        client_id: OIDC.clientId
      }),
      cache: "no-store"
    });
    if (!res.ok) return null;
    return (await res.json()) as TokenSet;
  } catch {
    return null;
  }
}

/** A safe same-origin path for post-login redirects (defends against open-redirect via ?from=). */
export function safePath(from: string | null | undefined): string {
  return from && from.startsWith("/") && !from.startsWith("//") ? from : "/";
}
