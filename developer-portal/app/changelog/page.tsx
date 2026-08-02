import Link from "next/link";
import { Header } from "../components/Header";
import { openApiUrl, routeTableUrl } from "../lib/gateway";

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

export default function ChangelogPage() {
  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Changelog</h1>
          <p className="intro__lede">
            What&rsquo;s new at the edge. Breaking changes are called out here and a deprecated route always
            ships a <code>Sunset</code> date before it&rsquo;s retired.
          </p>
        </section>

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
