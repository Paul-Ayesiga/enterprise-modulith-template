import type { RouteSummary } from "../lib/gateway";

/** The edge policy for one route as a definition list — what a caller must send and what the edge enforces. */
export function PolicyView({ route }: { route: RouteSummary }) {
  const t = route.traffic;
  const items: { label: string; value: string }[] = [];

  items.push({ label: "Access", value: route.authenticated ? "Bearer token required" : "Open — no token" });
  if (route.scopes && route.scopes.length > 0) {
    items.push({ label: "Scopes", value: route.scopes.join(", ") });
  }
  items.push({
    label: "Lifecycle",
    value: titleCase(route.lifecycle) + (route.sunset ? ` · sunset ${route.sunset}` : "")
  });

  if (t) {
    items.push({ label: "Rate limit", value: t.rateLimited ? "Enforced — per caller" : "None" });
    if (t.responseTimeoutMs != null) items.push({ label: "Timeout", value: `${t.responseTimeoutMs} ms` });
    if (t.maxRequestBytes != null) items.push({ label: "Max body", value: humanBytes(t.maxRequestBytes) });
    if (t.circuitBreaker) items.push({ label: "Circuit breaker", value: "Enabled" });
    if (t.retries) items.push({ label: "Retries", value: `${t.retries} (idempotent GETs)` });
    if (t.cacheTtlSeconds != null) items.push({ label: "Response cache", value: `${t.cacheTtlSeconds}s TTL` });
  }

  return (
    <dl className="policy">
      {items.map((item) => (
        <div className="policy__row" key={item.label}>
          <dt className="policy__key">{item.label}</dt>
          <dd className="policy__val">{item.value}</dd>
        </div>
      ))}
    </dl>
  );
}

function titleCase(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1).toLowerCase();
}

function humanBytes(n: number): string {
  if (n >= 1024 * 1024) return `${Math.round(n / 1024 / 1024)} MB`;
  if (n >= 1024) return `${Math.round(n / 1024)} KB`;
  return `${n} B`;
}
