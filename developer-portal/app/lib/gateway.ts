// Types + fetchers for the gateway's management endpoints (gatewaycatalog / gatewayopenapi).
// These are read server-side (server components), so there is no CORS or auth to negotiate in dev.

export type RouteSummary = {
  id: string;
  paths: string[];
  lifecycle: "PUBLISHED" | "DEPRECATED" | "RETIRED" | string;
  authenticated: boolean;
};

export type Product = {
  id: string;
  name: string;
  description: string;
  routes: RouteSummary[];
};

/** The gateway's admin (management) base URL — the catalog + OpenAPI live here. */
export const adminBaseUrl = (): string =>
  process.env.GATEWAY_ADMIN_URL ?? "http://localhost:9090";

export const openApiUrl = (): string => `${adminBaseUrl()}/actuator/gatewayopenapi`;

/** Fetch the product catalog. Returns [] and the error message rather than throwing, so the page renders. */
export async function fetchCatalog(): Promise<{ products: Product[]; error: string | null }> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewaycatalog`, { cache: "no-store" });
    if (!res.ok) {
      return { products: [], error: `Gateway responded ${res.status} ${res.statusText}` };
    }
    return { products: (await res.json()) as Product[], error: null };
  } catch (e) {
    return { products: [], error: e instanceof Error ? e.message : String(e) };
  }
}
