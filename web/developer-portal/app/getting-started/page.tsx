import Link from "next/link";
import { Header } from "../components/Header";
import { getSession } from "../lib/auth";
import { apiBaseUrl, openApiUrl, routeTableUrl } from "../lib/gateway";

export const dynamic = "force-dynamic";

/** The onboarding walk: sign in → first call → mint a key → go to production. */
export default function GettingStartedPage() {
  const signedIn = getSession() !== null;
  const base = apiBaseUrl();

  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Getting started</h1>
          <p className="intro__lede">
            From zero to your first authenticated API call in four steps. Everything here runs against
            the live gateway at <code>{base}</code>.
          </p>
        </section>

        <ol style={{ display: "grid", gap: "1rem", paddingLeft: 0, listStyle: "none", counterReset: "step" }}>
          <Step n={1} title="Sign in">
            {signedIn ? (
              <>
                You&rsquo;re signed in. Your session already authenticates the{" "}
                <Link href="/try">Try&nbsp;it</Link> console and the self-service pages.
              </>
            ) : (
              <>
                <a href="/api/auth/login">Sign in</a> with your SMSONE account. Your session then
                authenticates the <Link href="/try">Try&nbsp;it</Link> console and the self-service pages —
                no tokens to copy around.
              </>
            )}
          </Step>

          <Step n={2} title="Make your first call">
            Open <Link href="/try">Try it</Link>, keep <em>My session</em> selected, and send{" "}
            <code>GET /api/v1/me</code> — your own profile, through the gateway. Or from a terminal with a
            bearer token:
            <pre className="mono" style={{ overflowX: "auto", padding: "0.75rem 1rem", marginTop: 8 }}>
{`curl ${base}/api/v1/me \\
  -H "Authorization: Bearer $TOKEN"`}
            </pre>
          </Step>

          <Step n={3} title="Mint an API key for your servers">
            Interactive logins are for people; servers use keys. Under{" "}
            <Link href="/credentials">Credentials</Link>, mint a key carrying only the permissions your
            integration needs — the secret is shown once. Then call with the <code>X-Api-Key</code> header:
            <pre className="mono" style={{ overflowX: "auto", padding: "0.75rem 1rem", marginTop: 8 }}>
{`curl ${base}/api/v1/orgs/$ORG_ID/members \\
  -H "X-Api-Key: sk_yourprefix.your-full-secret"`}
            </pre>
          </Step>

          <Step n={4} title="React to events, watch your usage">
            Subscribe an endpoint under <Link href="/webhooks">Webhooks</Link> to get signed deliveries
            (HMAC-SHA256 in <code>X-Webhook-Signature</code>) instead of polling, and keep an eye on your
            plan&rsquo;s quota window under <Link href="/usage">Usage</Link>. The full endpoint reference
            lives in <Link href="/reference">Reference</Link>, always in sync with the deployed gateway.
          </Step>
        </ol>

        <p className="field__hint" style={{ marginTop: "1.25rem" }}>
          Conventions worth knowing from the start: every response is one envelope (<code>data</code> or{" "}
          <code>errors</code>, always <code>meta.requestId</code> — quote it in support tickets), lists page
          by cursor (<code>page[size]</code> / <code>page[after]</code>, follow <code>links.next</code>), and
          429 answers carry <code>Retry-After</code>.
        </p>
      </main>

      <footer className="site-footer">
        <p>
          Stuck? <Link href="/support">Support</Link> has the FAQ and the way in. Back to the{" "}
          <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <li className="panel" style={{ padding: "1rem 1.25rem" }}>
      <h2 style={{ fontSize: "1rem", margin: 0, display: "flex", alignItems: "center", gap: 10 }}>
        <span
          aria-hidden
          style={{
            display: "inline-flex",
            alignItems: "center",
            justifyContent: "center",
            width: 24,
            height: 24,
            borderRadius: 999,
            background: "var(--accent, #10218B)",
            color: "#fff",
            fontSize: "0.8rem",
            flexShrink: 0
          }}
        >
          {n}
        </span>
        {title}
      </h2>
      <p style={{ margin: "0.5rem 0 0" }}>{children}</p>
    </li>
  );
}
