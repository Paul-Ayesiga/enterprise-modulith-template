// Types + fetchers for the gateway's management endpoints (gatewaycatalog / gatewayopenapi).
// Read server-side (React Server Components), so there is no CORS or token to negotiate in dev.

export type Lifecycle = "PUBLISHED" | "DEPRECATED" | "RETIRED";

/** A route's edge traffic protections. All fields optional — an older gateway may not report them. */
export type TrafficInfo = {
  rateLimited?: boolean;
  circuitBreaker?: boolean;
  responseTimeoutMs?: number;
  maxRequestBytes?: number;
  retries?: number;
  cacheTtlSeconds?: number;
};

export type RouteSummary = {
  id: string;
  paths: string[];
  lifecycle: Lifecycle | string;
  authenticated: boolean;
  scopes?: string[];
  sunset?: string | null;
  traffic?: TrafficInfo;
};

export type Product = {
  id: string;
  name: string;
  description: string;
  routes: RouteSummary[];
};

export type CatalogResult = {
  products: Product[];
  error: string | null;
};

/** The gateway's admin (management) base URL — the catalog + OpenAPI live here. */
export function adminBaseUrl(): string {
  return process.env.GATEWAY_ADMIN_URL ?? "http://localhost:29090";
}

/** The gateway's public API base URL — the front door a caller actually hits (curl examples use it). */
export function apiBaseUrl(): string {
  return process.env.GATEWAY_API_URL ?? "http://localhost:28090";
}

/** The modulith's own base URL — where the full Swagger UI + OpenAPI + actuator health live (direct). */
export function docsBaseUrl(): string {
  return process.env.MODULITH_DOCS_URL ?? "http://localhost:28080";
}

export function openApiUrl(): string {
  return `${adminBaseUrl()}/actuator/gatewayopenapi`;
}

export function routeTableUrl(): string {
  return `${adminBaseUrl()}/actuator/gatewayroutes`;
}

/**
 * Fetch the product catalog. Never throws: returns an error message the page renders as a state,
 * rather than crashing the render tree.
 */
export async function fetchCatalog(): Promise<CatalogResult> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewaycatalog`, {
      cache: "no-store",
      headers: { accept: "application/json" }
    });
    if (!res.ok) {
      return { products: [], error: `Gateway responded ${res.status} ${res.statusText}` };
    }
    const products = (await res.json()) as Product[];
    return { products: Array.isArray(products) ? products : [], error: null };
  } catch (e) {
    return { products: [], error: e instanceof Error ? e.message : String(e) };
  }
}

/** Total route count across all products — for the summary line. */
export function totalRoutes(products: Product[]): number {
  return products.reduce((n, p) => n + p.routes.length, 0);
}
