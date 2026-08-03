// "My usage" data: the org's live quota window from the gateway's usage endpoint (the same Valkey
// counter the edge enforces against) + the plan/entitlements from the platform subscription API.
import { adminBaseUrl, adminHeaders, apiBaseUrl } from "./gateway";

export type MyUsage = {
  consumer: string;
  used: number;
  limited: boolean;
  limit: number | null;
  windowSeconds: number | null;
  remaining: number | null;
  resetSeconds: number;
};

export type MySubscription = {
  planCode: string;
  planName: string;
  status: string;
  trialEndsAt: string | null;
  entitlements: Record<string, number | null>;
};

export async function fetchUsageFor(orgId: string): Promise<{ usage: MyUsage | null; error: string | null }> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayusage/${encodeURIComponent(orgId)}`, {
      cache: "no-store",
      headers: { accept: "application/json", ...adminHeaders() }
    });
    if (!res.ok) {
      return { usage: null, error: `The gateway usage endpoint answered ${res.status} — is the gateway running?` };
    }
    return { usage: (await res.json()) as MyUsage, error: null };
  } catch (e) {
    return { usage: null, error: e instanceof Error ? e.message : String(e) };
  }
}

export async function fetchSubscription(
  token: string,
  orgId: string
): Promise<{ sub: MySubscription | null; error: string | null }> {
  try {
    const res = await fetch(`${apiBaseUrl()}/api/v1/orgs/${encodeURIComponent(orgId)}/subscription`, {
      cache: "no-store",
      headers: { authorization: `Bearer ${token}`, accept: "application/json" }
    });
    if (!res.ok) {
      return { sub: null, error: `Couldn't read your subscription (${res.status}).` };
    }
    const body = (await res.json()) as {
      data?: { attributes?: MySubscription };
    };
    return { sub: body?.data?.attributes ?? null, error: null };
  } catch (e) {
    return { sub: null, error: e instanceof Error ? e.message : String(e) };
  }
}
