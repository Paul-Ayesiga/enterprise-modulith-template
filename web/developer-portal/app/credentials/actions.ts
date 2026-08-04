"use server";

import { revalidatePath } from "next/cache";
import { getSession } from "../lib/auth";
import { createKey, getActiveOrg, revokeKey, rotateKey, type Minted } from "../lib/credentials";

export type MintState = { ok: boolean; message: string; minted?: Minted } | null;
export type ActionResult = { ok: boolean; message: string; minted?: Minted };

export async function createKeyAction(_prev: MintState, formData: FormData): Promise<MintState> {
  const session = getSession();
  if (!session) return { ok: false, message: "Your session expired — sign in again." };

  const name = String(formData.get("name") ?? "").trim();
  if (!name) return { ok: false, message: "A name is required." };
  const permissions = String(formData.get("permissions") ?? "")
    .split(",")
    .map((p) => p.trim())
    .filter(Boolean);

  const { orgId, error } = await getActiveOrg(session.token);
  if (!orgId) return { ok: false, message: error ?? "No active organization on your token." };

  const result = await createKey(session.token, orgId, name, permissions);
  if (!result.minted) return { ok: false, message: result.error ?? "Create failed." };
  revalidatePath("/credentials");
  return { ok: true, message: `Key "${name}" created.`, minted: result.minted };
}

export async function revokeKeyAction(id: string): Promise<ActionResult> {
  const session = getSession();
  if (!session) return { ok: false, message: "Your session expired — sign in again." };
  const { orgId } = await getActiveOrg(session.token);
  if (!orgId) return { ok: false, message: "No active organization on your token." };
  const { error } = await revokeKey(session.token, orgId, id);
  if (error) return { ok: false, message: error };
  revalidatePath("/credentials");
  return { ok: true, message: "Key revoked." };
}

export async function rotateKeyAction(id: string): Promise<ActionResult> {
  const session = getSession();
  if (!session) return { ok: false, message: "Your session expired — sign in again." };
  const { orgId } = await getActiveOrg(session.token);
  if (!orgId) return { ok: false, message: "No active organization on your token." };
  const { minted, error } = await rotateKey(session.token, orgId, id);
  if (!minted) return { ok: false, message: error ?? "Rotate failed." };
  revalidatePath("/credentials");
  return { ok: true, message: "Key rotated — copy the new secret now.", minted };
}
