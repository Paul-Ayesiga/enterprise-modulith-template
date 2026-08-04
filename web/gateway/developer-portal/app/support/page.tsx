import Link from "next/link";
import type { ReactNode } from "react";
import { ExternalIcon } from "../components/Icons";
import { apiBaseUrl, docsBaseUrl, openApiUrl, routeTableUrl } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default function SupportPage() {
  const api = apiBaseUrl();
  const docs = docsBaseUrl();
  const resources: { label: string; href: string; note: string }[] = [
    { label: "OpenAPI (gateway)", href: openApiUrl(), note: "The routes the edge publishes" },
    { label: "Swagger UI (full API)", href: `${docs}/swagger-ui/index.html`, note: "Every operation, param, and schema" },
    { label: "Route table", href: routeTableUrl(), note: "The live gateway route table (JSON)" },
    { label: "Gateway health", href: `${docs}/actuator/health`, note: "Liveness / readiness" }
  ];

  const faqs: { q: string; a: ReactNode }[] = [
    {
      q: "How do I get a token?",
      a: (
        <>
          Run <code>make token</code> (or <code>scripts/token.sh</code>) for a dev access token, then paste it
          into <Link href="/try">Try it</Link> or an <code>Authorization: Bearer</code> header.
        </>
      )
    },
    {
      q: "Where do I send requests?",
      a: (
        <>
          Through the gateway front door at <code>{api}/api/v1/…</code> — it validates your token, applies
          quotas and tracing, then proxies to the backend. You can also hit the app directly at{" "}
          <code>{docs}</code> during local dev.
        </>
      )
    },
    {
      q: "Why did I get a 401 or 403?",
      a: (
        <>
          <strong>401</strong> — no valid token (expired, or missing the <code>Authorization</code> header).{" "}
          <strong>403</strong> — the token is valid but lacks the required scope or org permission for that
          route. Each route&rsquo;s access rule is on its product page.
        </>
      )
    },
    {
      q: "Why did I get a 429?",
      a: (
        <>
          The edge is rate-limiting or quota-limiting you. Limits are <em>per caller</em>, so a single client
          bursting will hit them fast — back off and retry, or spread traffic across principals. A 429 means
          shedding, not an outage.
        </>
      )
    },
    {
      q: "How are breaking changes handled?",
      a: (
        <>
          A route is marked <strong>DEPRECATED</strong> with a <code>Sunset</code> date well before it becomes{" "}
          <strong>RETIRED</strong>. Watch the <Link href="/changelog">changelog</Link> and the lifecycle badge
          on each route.
        </>
      )
    }
  ];

  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Support</h1>
          <p className="intro__lede">
            The essentials for integrating against the gateway — tokens, endpoints, error codes, and where the
            full reference lives.
          </p>
        </section>

        <section className="section">
          <h2 className="section__title">Resources</h2>
          <ul className="resource-grid" role="list">
            {resources.map((r) => (
              <li key={r.label}>
                <a className="resource" href={r.href} target="_blank" rel="noreferrer">
                  <span className="resource__label">
                    {r.label} <ExternalIcon />
                  </span>
                  <span className="resource__note">{r.note}</span>
                </a>
              </li>
            ))}
          </ul>
        </section>

        <section className="section">
          <h2 className="section__title">FAQ</h2>
          <div className="faq">
            {faqs.map((f, i) => (
              <details className="faq__item" key={i}>
                <summary className="faq__q">{f.q}</summary>
                <div className="faq__a">{f.a}</div>
              </details>
            ))}
          </div>
        </section>
      </main>

      <footer className="site-footer">
        <p>
          Back to the <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}
