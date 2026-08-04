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

/** The management-port credential (gateway.admin.token) — sent as X-Admin-Token when configured. */
export function adminHeaders(): Record<string, string> {
  const token = process.env.GATEWAY_ADMIN_TOKEN;
  return token ? { "x-admin-token": token } : {};
}

/** A route the gateway currently announces as deprecated (or already retired). */
export type DeprecatedRoute = { id: string; path: string; lifecycle: string; sunset: string | null };

/**
 * Live deprecations from the route table — the changelog section that must never go stale, so it is
 * rendered from the gateway rather than written by hand. Unreachable gateway → empty with an error.
 */
export async function fetchDeprecations(): Promise<{ deprecated: DeprecatedRoute[]; error: string | null }> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewayroutes`, {
      cache: "no-store",
      headers: { accept: "application/json", ...adminHeaders() }
    });
    if (!res.ok) {
      return { deprecated: [], error: `Gateway responded ${res.status}` };
    }
    const routes = (await res.json()) as DeprecatedRoute[];
    const deprecated = (Array.isArray(routes) ? routes : [])
      .filter((r) => r.lifecycle === "DEPRECATED" || r.lifecycle === "RETIRED")
      .sort((a, b) => a.lifecycle.localeCompare(b.lifecycle) || a.id.localeCompare(b.id));
    return { deprecated, error: null };
  } catch (e) {
    return { deprecated: [], error: e instanceof Error ? e.message : String(e) };
  }
}

/**
 * Fetch the product catalog. Never throws: returns an error message the page renders as a state,
 * rather than crashing the render tree.
 */
export async function fetchCatalog(): Promise<CatalogResult> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/gatewaycatalog`, {
      cache: "no-store",
      headers: { accept: "application/json", ...adminHeaders() }
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

// ---- OpenAPI (the modulith's full spec, for the API reference) ----

export type OpenApiParam = {
  name: string;
  in: string;
  required?: boolean;
  description?: string;
  type?: string;
};
export type OpenApiOperation = {
  method: string;
  path: string;
  tag: string;
  summary?: string;
  description?: string;
  operationId?: string;
  deprecated: boolean;
  parameters: OpenApiParam[];
  requestContentTypes: string[];
  responses: { status: string; description?: string }[];
};
export type OpenApiDoc = { title: string; version: string; tags: string[]; operations: OpenApiOperation[] };

type RawParam = { name?: string; in?: string; required?: boolean; description?: string; schema?: { type?: string } };
type RawOp = {
  tags?: string[];
  summary?: string;
  description?: string;
  operationId?: string;
  deprecated?: boolean;
  parameters?: RawParam[];
  requestBody?: { content?: Record<string, unknown> };
  responses?: Record<string, { description?: string }>;
};
type RawSpec = {
  info?: { title?: string; version?: string };
  paths?: Record<string, Record<string, RawOp>>;
};

const HTTP_METHODS = ["get", "post", "put", "patch", "delete", "head", "options"];

/** Fetch the modulith's full OpenAPI and flatten it into a tag-grouped operation list. Never throws. */
export async function fetchOpenApi(): Promise<{ doc: OpenApiDoc | null; error: string | null }> {
  try {
    const res = await fetch(`${docsBaseUrl()}/v3/api-docs`, {
      cache: "no-store",
      headers: { accept: "application/json" }
    });
    if (!res.ok) {
      return { doc: null, error: `OpenAPI responded ${res.status} ${res.statusText}` };
    }
    return { doc: flatten((await res.json()) as RawSpec), error: null };
  } catch (e) {
    return { doc: null, error: e instanceof Error ? e.message : String(e) };
  }
}

function flatten(raw: RawSpec): OpenApiDoc {
  const operations: OpenApiOperation[] = [];
  for (const [path, methods] of Object.entries(raw.paths ?? {})) {
    for (const [method, op] of Object.entries(methods)) {
      if (!HTTP_METHODS.includes(method)) continue;
      operations.push({
        method: method.toUpperCase(),
        path,
        tag: op.tags?.[0] ?? "Other",
        summary: op.summary,
        description: op.description,
        operationId: op.operationId,
        deprecated: Boolean(op.deprecated),
        parameters: (op.parameters ?? []).map((p) => ({
          name: p.name ?? "",
          in: p.in ?? "",
          required: p.required,
          description: p.description,
          type: p.schema?.type
        })),
        requestContentTypes: op.requestBody?.content ? Object.keys(op.requestBody.content) : [],
        responses: Object.entries(op.responses ?? {}).map(([status, r]) => ({ status, description: r?.description }))
      });
    }
  }
  operations.sort((a, b) => a.tag.localeCompare(b.tag) || a.path.localeCompare(b.path));
  const tags = [...new Set(operations.map((o) => o.tag))].sort();
  return {
    title: raw.info?.title ?? "API",
    version: raw.info?.version ?? "",
    tags,
    operations
  };
}
