// Org webhook subscriptions, driven with the signed-in user's token through the gateway front door
// (the same pattern as credentials.ts). The signing secret is full ONLY in the create response.
import { apiBaseUrl } from "./gateway";

export type Webhook = {
  id: string;
  url: string;
  events: string[];
  status: string;
  secret: string; // masked everywhere except the create response
};

export type EventType = { code: string; description: string };

export type Delivery = {
  id: string;
  eventType: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  responseStatus: number | null;
  lastError: string | null;
};

type Resource<A> = { id: string; attributes: A };

async function call(token: string, path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${apiBaseUrl()}${path}`, {
    ...init,
    headers: { authorization: `Bearer ${token}`, accept: "application/json", ...(init?.headers ?? {}) },
    cache: "no-store"
  });
}

function fromResource(r: Resource<Omit<Webhook, "id">>): Webhook {
  return { id: r.id, ...r.attributes };
}

export async function listEventTypes(token: string): Promise<{ types: EventType[]; error: string | null }> {
  const res = await call(token, "/api/v1/webhooks/event-types");
  if (!res.ok) return { types: [], error: `Couldn't load event types (${res.status}).` };
  const body = (await res.json()) as { data?: Resource<EventType>[] };
  return { types: (body.data ?? []).map((r) => r.attributes), error: null };
}

export async function listWebhooks(token: string, orgId: string): Promise<{ webhooks: Webhook[]; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/webhooks?page%5Bsize%5D=100`);
  if (!res.ok) return { webhooks: [], error: `Couldn't load webhooks (${res.status}).` };
  const body = (await res.json()) as { data?: Resource<Omit<Webhook, "id">>[] };
  return { webhooks: (body.data ?? []).map(fromResource), error: null };
}

export async function createWebhook(
  token: string,
  orgId: string,
  url: string,
  events: string[]
): Promise<{ created: Webhook | null; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/webhooks`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ url, events })
  });
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as { errors?: { detail?: string }[] } | null;
    return { created: null, error: body?.errors?.[0]?.detail ?? `Create failed (${res.status}).` };
  }
  const body = (await res.json()) as { data?: Resource<Omit<Webhook, "id">> };
  return { created: body.data ? fromResource(body.data) : null, error: null };
}

export async function deleteWebhook(token: string, orgId: string, id: string): Promise<{ error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/webhooks/${id}`, { method: "DELETE" });
  if (!res.ok && res.status !== 404) return { error: `Delete failed (${res.status}).` };
  return { error: null };
}

export async function listDeliveries(
  token: string,
  orgId: string,
  id: string
): Promise<{ deliveries: Delivery[]; error: string | null }> {
  const res = await call(token, `/api/v1/orgs/${orgId}/webhooks/${id}/deliveries?page%5Bsize%5D=25`);
  if (!res.ok) return { deliveries: [], error: `Couldn't load deliveries (${res.status}).` };
  const body = (await res.json()) as { data?: Resource<Omit<Delivery, "id">>[] };
  return { deliveries: (body.data ?? []).map((r) => ({ id: r.id, ...r.attributes })), error: null };
}
