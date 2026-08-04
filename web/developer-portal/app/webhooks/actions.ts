"use server";

import { revalidatePath } from "next/cache";
import { getSession } from "../lib/auth";
import { getActiveOrg } from "../lib/credentials";
import { createWebhook, deleteWebhook, type Webhook } from "../lib/webhooks";

export type CreateState = { ok: boolean; message: string; created?: Webhook } | null;
export type ActionResult = { ok: boolean; message: string };

export async function createWebhookAction(_prev: CreateState, formData: FormData): Promise<CreateState> {
  const session = getSession();
  if (!session) return { ok: false, message: "Your session expired — sign in again." };

  const url = String(formData.get("url") ?? "").trim();
  const events = formData.getAll("events").map(String).filter(Boolean);
  if (!url.startsWith("http")) return { ok: false, message: "The endpoint must be an http(s) URL." };
  if (events.length === 0) return { ok: false, message: "Pick at least one event." };

  const { orgId, error } = await getActiveOrg(session.token);
  if (!orgId) return { ok: false, message: error ?? "No active organization on your token." };

  const { created, error: createError } = await createWebhook(session.token, orgId, url, events);
  if (!created) return { ok: false, message: createError ?? "Create failed." };
  revalidatePath("/webhooks");
  return { ok: true, message: "Webhook created — copy the signing secret now, it is shown only once.", created };
}

export async function deleteWebhookAction(id: string): Promise<ActionResult> {
  const session = getSession();
  if (!session) return { ok: false, message: "Your session expired — sign in again." };
  const { orgId } = await getActiveOrg(session.token);
  if (!orgId) return { ok: false, message: "No active organization on your token." };
  const { error } = await deleteWebhook(session.token, orgId, id);
  if (error) return { ok: false, message: error };
  revalidatePath("/webhooks");
  return { ok: true, message: "Webhook removed." };
}
