"use server";

import { revalidatePath } from "next/cache";
import { adminBaseUrl } from "./gateway";

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

  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ id, path, serviceId }),
      cache: "no-store"
    });
    if (!res.ok) {
      const detail = await safeMessage(res);
      return { ok: false, message: `Gateway rejected the route (${res.status})${detail ? `: ${detail}` : ""}.` };
    }
    revalidatePath("/");
    return { ok: true, message: `Route "${id}" registered — it is live on the edge now.` };
  } catch (e) {
    return { ok: false, message: `Can't reach the gateway admin API: ${msg(e)}` };
  }
}

/** Remove a route via the gatewayroutes delete operation. */
export async function deleteRoute(id: string): Promise<ActionState> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes/${encodeURIComponent(id)}`, {
      method: "DELETE",
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
