// Types + fetchers for the gateway's management endpoints (gatewayroutes / gatewaycatalog / health).
// Read server-side (React Server Components) against the admin port, so there is no CORS or token here.

export type Lifecycle = "PUBLISHED" | "DEPRECATED" | "RETIRED" | string;

/** A route as the gatewayroutes read operation returns it, enriched with catalog lifecycle/product. */
export type RouteRow = {
  id: string;
  order: number;
  serviceId: string;
  authenticated: boolean;
  predicates: string[];
  paths: string[];
  lifecycle: Lifecycle;
  rateLimited: boolean;
  product: string | null;
};

export type ServiceRow = {
  id: string;
  routeCount: number;
};

/** One consumer's live quota window, from the gatewayusage endpoint (top talkers first). */
export type UsageRow = {
  consumer: string;
  used: number;
  limited: boolean;
  limit: number | null;
  windowSeconds: number | null;
  remaining: number | null;
  resetSeconds: number;
};

/** One deny-list entry from the gatewayblocklist endpoint. Auto entries carry a TTL. */
export type BlocklistEntry = {
  cidr: string;
  source: "config" | "runtime" | "auto" | string;
  expiresInSeconds?: number;
};

/** The live auto-blocking rules, as the endpoint reports them. */
export type AutoBlockRules = {
  enabled: boolean;
  window: string;
  threshold: number;
  blockDuration: string;
  statuses: number[];
};

export type Blocklist = {
  entries: BlocklistEntry[];
  allow: string[];
  trustedProxyHops: number;
  autoBlock: AutoBlockRules;
  error: string | null;
};

export type Overview = {
  routes: RouteRow[];
  services: ServiceRow[];
  productCount: number;
  health: string;
  error: string | null;
};

/** The gateway's admin (management) base URL — routes, catalog, health, and the write operations. */
export function adminBaseUrl(): string {
  return process.env.GATEWAY_ADMIN_URL ?? "http://localhost:29090";
}

/** The management-port credential (gateway.admin.token) — sent as X-Admin-Token when configured. */
export function adminHeaders(): Record<string, string> {
  const token = process.env.GATEWAY_ADMIN_TOKEN;
  return token ? { "x-admin-token": token } : {};
}

export function grafanaUrl(): string {
  return process.env.GRAFANA_URL ?? "http://localhost:23000";
}

export function prometheusUrl(): string {
  return `${adminBaseUrl()}/actuator/prometheus`;
}

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${adminBaseUrl()}${path}`, {
    cache: "no-store",
    headers: { accept: "application/json", ...adminHeaders() }
  });
  if (!res.ok) {
    throw new Error(`${path} → ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

type RawRoute = {
  id: string;
  order: number;
  serviceId: string;
  authenticated: boolean;
  predicates: string[];
  // First-class from the gatewayroutes read since the update op landed; older gateways omit them.
  path?: string;
  lifecycle?: string;
  rateLimited?: boolean;
};
type CatalogProduct = { id: string; name: string; routes: { id: string; paths: string[]; lifecycle: string }[] };

/** Load the live route table, joined with the catalog for each route's paths, lifecycle, and product. */
export async function fetchOverview(): Promise<Overview> {
  try {
    const [raw, catalog] = await Promise.all([
      getJson<RawRoute[]>("/actuator/gatewayroutes"),
      getJson<CatalogProduct[]>("/actuator/gatewaycatalog").catch(() => [] as CatalogProduct[])
    ]);

    // Index catalog routes by id → { paths, lifecycle, product }.
    const byRoute = new Map<string, { paths: string[]; lifecycle: string; product: string }>();
    for (const product of catalog) {
      for (const route of product.routes) {
        byRoute.set(route.id, { paths: route.paths, lifecycle: route.lifecycle, product: product.id });
      }
    }

    const routes: RouteRow[] = raw
      .map((r) => {
        const meta = byRoute.get(r.id);
        return {
          id: r.id,
          order: r.order,
          serviceId: r.serviceId,
          authenticated: r.authenticated,
          predicates: r.predicates ?? [],
          paths: r.path ? [r.path] : meta?.paths ?? pathsFromPredicates(r.predicates ?? []),
          lifecycle: r.lifecycle ?? meta?.lifecycle ?? "PUBLISHED",
          rateLimited: r.rateLimited ?? false,
          product: meta?.product ?? null
        };
      })
      .sort((a, b) => a.order - b.order || a.id.localeCompare(b.id));

    const counts = new Map<string, number>();
    for (const r of routes) {
      counts.set(r.serviceId, (counts.get(r.serviceId) ?? 0) + 1);
    }
    const services: ServiceRow[] = [...counts.entries()]
      .map(([id, routeCount]) => ({ id, routeCount }))
      .sort((a, b) => a.id.localeCompare(b.id));

    const productCount = new Set(routes.map((r) => r.product).filter(Boolean)).size;
    const health = await fetchHealth();

    return { routes, services, productCount, health, error: null };
  } catch (e) {
    return {
      routes: [],
      services: [],
      productCount: 0,
      health: "unknown",
      error: e instanceof Error ? e.message : String(e)
    };
  }
}

/** Live per-consumer usage — never throws; an unreachable endpoint renders as an error state. */
const AUTO_RULES_OFF: AutoBlockRules = {
  enabled: false,
  window: "PT1M",
  threshold: 0,
  blockDuration: "PT15M",
  statuses: []
};

/** The edge deny-list — every entry refuses matching sources before auth, quotas, or routing. */
export async function fetchBlocklist(): Promise<Blocklist> {
  try {
    const raw = await getJson<{
      entries: BlocklistEntry[];
      allow: string[];
      trustedProxyHops: number;
      autoBlock: AutoBlockRules;
    }>("/actuator/gatewayblocklist");
    return {
      entries: raw.entries,
      allow: raw.allow ?? [],
      trustedProxyHops: raw.trustedProxyHops,
      autoBlock: raw.autoBlock ?? AUTO_RULES_OFF,
      error: null
    };
  } catch (e) {
    return {
      entries: [],
      allow: [],
      trustedProxyHops: 0,
      autoBlock: AUTO_RULES_OFF,
      error: e instanceof Error ? e.message : String(e)
    };
  }
}

export async function fetchUsage(): Promise<{ usage: UsageRow[]; error: string | null }> {
  try {
    const usage = await getJson<UsageRow[]>("/actuator/gatewayusage");
    return { usage: Array.isArray(usage) ? usage : [], error: null };
  } catch (e) {
    return { usage: [], error: e instanceof Error ? e.message : String(e) };
  }
}

export async function fetchHealth(): Promise<string> {
  try {
    const res = await fetch(`${adminBaseUrl()}/actuator/health`, { cache: "no-store" });  // probes stay tokenless
    if (!res.ok) return "DOWN";
    const body = (await res.json()) as { status?: string };
    return body.status ?? "UNKNOWN";
  } catch {
    return "unknown";
  }
}

/** Best-effort recovery of a route's paths when the catalog didn't list it (predicates like "PATH [/a, /b]"). */
function pathsFromPredicates(predicates: string[]): string[] {
  const out: string[] = [];
  for (const p of predicates) {
    if (!p.startsWith("PATH")) continue;
    const inside = p.slice(p.indexOf("[") + 1, p.lastIndexOf("]"));
    for (const part of inside.split(",")) {
      const path = part.trim();
      if (path) out.push(path);
    }
  }
  return out;
}
