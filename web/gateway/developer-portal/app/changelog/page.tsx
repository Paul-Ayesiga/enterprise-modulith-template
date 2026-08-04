import Link from "next/link";
import { fetchDeprecations, type DeprecatedRoute } from "../lib/gateway";

export const dynamic = "force-dynamic";

type Entry = { when: string; title: string; points: string[] };

// A curated changelog of the gateway's developer-facing capabilities. Newest first.
const ENTRIES: Entry[] = [
  {
    when: "Aug 2026",
    title: "API products + a richer portal",
    points: [
      "The API surface is grouped into eight products (identity, organizations, configuration, files & documents, notifications, audit, operations, plus a platform catch-all).",
      "Interactive “try it” console — send a live request through the gateway from the browser.",
      "Per-product detail pages surfacing each route’s edge policy (rate limit, timeout, body cap, cache).",
      "Copy-as-curl on every route."
    ]
  },
  {
    when: "Aug 2026",
    title: "Reliability fix under load",
    points: [
      "Rate-limited requests now return a clean 429. Previously, under concurrency, an edge filter interaction could surface a 500 instead — that path is fixed.",
      "Load-test metrics stream to Grafana over OTLP (the “SMSOne · k6 Load” dashboard)."
    ]
  },
  {
    when: "Foundation",
    title: "The edge",
    points: [
      "JWT validation at the edge against the IdP’s JWKS (no platform round-trip); API-key introspection for X-Api-Key.",
      "Per-caller rate limiting, per-consumer plan quotas, circuit breaking, per-route timeouts, response caching, and gzip compression.",
      "Coarse per-route auth policy (token / scopes / path-tenant); services keep their own fine-grained checks.",
      "Route lifecycle — PUBLISHED / DEPRECATED (with a Sunset header) / RETIRED.",
      "Separable observability streams (access / security / error) and OpenTelemetry traces + metrics."
    ]
  }
];

export default async function ChangelogPage() {
  const { deprecated, error } = await fetchDeprecations();

  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Changelog</h1>
          <p className="intro__lede">
            What&rsquo;s new at the edge. Breaking changes are called out here and a deprecated route always
            ships a <code>Sunset</code> date before it&rsquo;s retired.
          </p>
        </section>

        <Deprecations deprecated={deprecated} error={error} />

        <ol className="changelog" role="list">
          {ENTRIES.map((entry, i) => (
            <li className="changelog__item" key={i}>
              <div className="changelog__when">{entry.when}</div>
              <div className="changelog__body">
                <h2 className="changelog__title">{entry.title}</h2>
                <ul className="changelog__points">
                  {entry.points.map((p, j) => (
                    <li key={j}>{p}</li>
                  ))}
                </ul>
              </div>
            </li>
          ))}
        </ol>
      </main>

      <footer className="site-footer">
        <p>
          Back to the <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}

/**
 * Rendered LIVE from the gateway's route table — never hand-maintained, so it cannot go stale. A
 * stale deprecation notice is worse than none; this section is the part of a changelog that must be
 * trustworthy by construction.
 */
function Deprecations({ deprecated, error }: { deprecated: DeprecatedRoute[]; error: string | null }) {
  if (error) {
    return (
      <p className="field__hint" style={{ marginBottom: "1.25rem" }}>
        Live deprecation status unavailable right now ({error}).
      </p>
    );
  }
  if (deprecated.length === 0) {
    return (
      <section className="panel" style={{ padding: "0.9rem 1.1rem", marginBottom: "1.25rem" }}>
        <p style={{ margin: 0 }}>
          <span className="badge badge--published">Live</span>{" "}
          <strong>No deprecated endpoints right now</strong>
          <span className="field__hint" style={{ marginLeft: 8 }}>
            read from the gateway&rsquo;s route table on every visit
          </span>
        </p>
      </section>
    );
  }
  return (
    <section className="panel panel__scroll" style={{ marginBottom: "1.25rem" }}>
      <div style={{ padding: "0.9rem 1.1rem 0" }}>
        <strong>Current deprecations &amp; sunsets</strong>{" "}
        <span className="field__hint">live from the gateway — plan your migration</span>
      </div>
      <table className="keys-table">
        <thead>
          <tr>
            <th>Route</th>
            <th>Path</th>
            <th>Status</th>
            <th>Sunset</th>
          </tr>
        </thead>
        <tbody>
          {deprecated.map((route) => (
            <tr key={route.id}>
              <td className="mono">{route.id}</td>
              <td className="mono">{route.path || "—"}</td>
              <td>
                <span className={`badge badge--${route.lifecycle === "RETIRED" ? "retired" : "deprecated"}`}>
                  {route.lifecycle === "RETIRED" ? "RETIRED (410)" : "DEPRECATED"}
                </span>
              </td>
              <td>{route.sunset ?? "not announced yet"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}
