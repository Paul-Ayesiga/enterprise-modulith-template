"use server";

import { revalidatePath } from "next/cache";
import { adminBaseUrl, adminHeaders } from "./gateway";

export type ActionState = { ok: boolean; message: string } | null;

/** Register (or replace) a route via the gatewayroutes write operation. Takes effect on the edge live. */
export async function createRoute(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const id = String(formData.get("id") ?? "").trim();
  const path = String(formData.get("path") ?? "").trim();
  const serviceId = String(formData.get("serviceId") ?? "").trim();

  if (!id || !path || !serviceId) {
    return { ok: false, message: "id, path, and service are all required." };
  }
  if (!/^[a-z0-9][a-z0-9-]*$/i.test(id)) {
    return { ok: false, message: "id must be alphanumeric with dashes, e.g. reports-api." };
  }
  if (!path.startsWith("/")) {
    return { ok: false, message: "path must start with '/', e.g. /api/v1/reports/**." };
  }
  // Checkbox-before-hidden pattern: get() sees "true" when checked, the hidden "false" otherwise.
  const authenticated = String(formData.get("authenticated") ?? "") === "true";
  const rateLimited = String(formData.get("rateLimited") ?? "") === "true";

  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes`, {
      method: "POST",
      headers: { "content-type": "application/json", ...adminHeaders() },
      body: JSON.stringify({ id, path, serviceId, authenticated, rateLimited }),
      cache: "no-store"
    });
    if (!res.ok) {
      const detail = await safeMessage(res);
      return { ok: false, message: `Gateway rejected the route (${res.status})${detail ? `: ${detail}` : ""}.` };
    }
    revalidatePath("/");
    return { ok: true, message: `Route "${id}" registered — live now. Runtime change: it survives until the gateway restarts; add it to the gateway YAML to keep it.` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}

/**
 * Edit a route in place via POST gatewayroutes/{id}. Only the provided fields change — the gateway
 * preserves auth/traffic/transform policies, exactly what delete-and-recreate would lose.
 */
export async function updateRoute(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const id = String(formData.get("id") ?? "").trim();
  const path = String(formData.get("path") ?? "").trim();
  const serviceId = String(formData.get("serviceId") ?? "").trim();
  const orderRaw = String(formData.get("order") ?? "").trim();
  const lifecycle = String(formData.get("lifecycle") ?? "").trim();

  if (!id) {
    return { ok: false, message: "Missing route id." };
  }
  if (path && !path.startsWith("/")) {
    return { ok: false, message: "path must start with '/', e.g. /api/v1/reports/**." };
  }
  const order = orderRaw === "" ? null : Number(orderRaw);
  if (order !== null && (!Number.isInteger(order) || order < 0)) {
    return { ok: false, message: "order must be a non-negative integer (lower wins)." };
  }

  const patch: Record<string, unknown> = {};
  if (path) patch.path = path;
  if (serviceId) patch.serviceId = serviceId;
  if (order !== null) patch.order = order;
  if (lifecycle) patch.lifecycle = lifecycle;
  patch.authenticated = String(formData.get("authenticated") ?? "") === "true";
  patch.rateLimited = String(formData.get("rateLimited") ?? "") === "true";
  if (Object.keys(patch).length === 0) {
    return { ok: false, message: "Nothing to change." };
  }

  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes/${encodeURIComponent(id)}`, {
      method: "POST",
      headers: { "content-type": "application/json", ...adminHeaders() },
      body: JSON.stringify(patch),
      cache: "no-store"
    });
    if (!res.ok) {
      const detail = await safeMessage(res);
      return { ok: false, message: `Gateway rejected the update (${res.status})${detail ? `: ${detail}` : ""}.` };
    }
    revalidatePath("/");
    return { ok: true, message: `Route "${id}" updated — live now. Runtime change: a gateway restart reverts to the YAML-configured route.` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}

/** One-click lifecycle flip: RETIRED pauses traffic (410 Gone), PUBLISHED resumes it. */
export async function setLifecycle(id: string, lifecycle: string): Promise<ActionState> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes/${encodeURIComponent(id)}`, {
      method: "POST",
      headers: { "content-type": "application/json", ...adminHeaders() },
      body: JSON.stringify({ lifecycle }),
      cache: "no-store"
    });
    if (!res.ok) {
      const detail = await safeMessage(res);
      return { ok: false, message: `Lifecycle change failed (${res.status})${detail ? `: ${detail}` : ""}.` };
    }
    revalidatePath("/");
    const verb = lifecycle === "RETIRED" ? "paused (410 on the edge)" : `set to ${lifecycle}`;
    const caveat = lifecycle === "RETIRED" ? " Runtime-only: a gateway restart un-pauses it." : "";
    return { ok: true, message: `Route "${id}" ${verb}.${caveat}` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}

/** Remove a route via the gatewayroutes delete operation. */
export async function deleteRoute(id: string): Promise<ActionState> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes/${encodeURIComponent(id)}`, {
      method: "DELETE",
      headers: adminHeaders(),
      cache: "no-store"
    });
    if (!res.ok && res.status !== 404) {
      return { ok: false, message: `Delete failed (${res.status}).` };
    }
    revalidatePath("/");
    return { ok: true, message: `Route "${id}" removed.` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}

async function safeMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { message?: string; error?: string };
    return body?.message ?? body?.error ?? "";
  } catch {
    return "";
  }
}

function msg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Block or unblock a source CIDR via the gatewayblocklist write operation. Bites the very next
 * request — the filter runs before auth and routing. Runtime change: durable blocks belong in
 * gateway.security.blocklist.cidrs.
 */
export async function updateBlocklist(_prev: ActionState, formData: FormData): Promise<ActionState> {
  const cidr = String(formData.get("cidr") ?? "").trim();
  const blocked = String(formData.get("blocked") ?? "true") === "true";
  if (!cidr) {
    return { ok: false, message: "An IP or CIDR is required, e.g. 203.0.113.0/24." };
  }
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayblocklist`, {
      method: "POST",
      headers: { "content-type": "application/json", ...adminHeaders() },
      body: JSON.stringify({ cidr, blocked }),
      cache: "no-store"
    });
    if (!res.ok) {
      return { ok: false, message: `Gateway refused the change (${res.status}).` };
    }
    const body = (await res.json()) as { error?: string; cidr?: string; removed?: boolean };
    if (body.error) {
      return { ok: false, message: body.error };
    }
    revalidatePath("/");
    return blocked
      ? { ok: true, message: `${body.cidr} is blocked — live now. Runtime change: add it to the gateway YAML to survive a restart.` }
      : { ok: true, message: body.removed ? `${cidr} unblocked.` : `${cidr} was not on the list.` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}
