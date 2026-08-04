// Org-scoped API-key calls, made with the logged-in user's bearer token through the gateway front door.
import { apiBaseUrl } from "./gateway";

export type ApiKey = {
  id: string;
  name: string;
  prefix: string;
  permissions: string[];
  platformTier?: string | null;
  expiresAt?: string | null;
  lastUsedAt?: string | null;
};

/** A freshly minted key — the only time the full secret is available. */
export type Minted = { name: string; prefix: string; secret: string; permissions: string[] };

async function call(token: string, path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${apiBaseUrl()}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, accept: "application/json", ...(init?.headers ?? {}) },
    cache: "no-store"
  });
}

export async function getActiveOrg(token: string): Promise<{ orgId: string | null; error: string | null }> {
  const res = await call(token, "/api/v1/me");
  if (!res.ok) return { orgId: null, error: `Couldn't read your profile (${res.status}).` };
  const body = await res.json();
  return { orgId: body?.data?.attributes?.activeOrgId ?? null, error: null };
}

export async function listKeys(token: string, orgId: string): Promise<{ keys: ApiKey[]; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/api-keys?page%5Bsize%5D=100`);
  if (res.status === 403) return { keys: [], error: "Your account lacks the apikey:manage permission in this org." };
  if (!res.ok) return { keys: [], error: `Couldn't list keys (${res.status}).` };
  const body = await res.json();
  const keys: ApiKey[] = (body?.data ?? []).map((d: { id: string; attributes: Omit<ApiKey, "id"> }) => ({
    id: d.id,
    ...d.attributes
  }));
  return { keys, error: null };
}

export async function createKey(
  token: string,
  orgId: string,
  name: string,
  permissions: string[]
): Promise<{ minted: Minted | null; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/api-keys`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name, permissions })
  });
  if (!res.ok) return { minted: null, error: await problem(res) };
  return { minted: minted(await res.json()), error: null };
}

export async function revokeKey(token: string, orgId: string, id: string): Promise<{ error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/api-keys/${id}`, { method: "DELETE" });
  if (!res.ok && res.status !== 404) return { error: `Revoke failed (${res.status}).` };
  return { error: null };
}

export async function rotateKey(
  token: string,
  orgId: string,
  id: string
): Promise<{ minted: Minted | null; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/api-keys/${id}/rotate`, { method: "POST" });
  if (!res.ok) return { minted: null, error: await problem(res) };
  return { minted: minted(await res.json()), error: null };
}

function minted(body: { data?: { attributes?: Partial<Minted> } }): Minted {
  const a = body?.data?.attributes ?? {};
  return { name: a.name ?? "", prefix: a.prefix ?? "", secret: a.secret ?? "", permissions: a.permissions ?? [] };
}

async function problem(res: Response): Promise<string> {
  try {
    const body = await res.json();
    return body?.errors?.[0]?.detail ?? body?.errors?.[0]?.title ?? `Request failed (${res.status}).`;
  } catch {
    return `Request failed (${res.status}).`;
  }
}
