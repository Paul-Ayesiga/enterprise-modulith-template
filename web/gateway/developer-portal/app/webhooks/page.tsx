import Link from "next/link";
import { LockIcon } from "../components/Icons";
import { Webhooks } from "../components/Webhooks";
import { getSession, type Session } from "../lib/auth";
import { getActiveOrg } from "../lib/credentials";
import { listDeliveries, listEventTypes, listWebhooks, type Delivery } from "../lib/webhooks";

export const dynamic = "force-dynamic";

export default function WebhooksPage({ searchParams }: { searchParams: { sub?: string } }) {
  const session = getSession();

  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Webhooks</h1>
          <p className="intro__lede">
            Push events to your systems as they happen — signed deliveries, automatic retries, and a
            per-subscription delivery log.
          </p>
        </section>

        {!session ? <LoginCta /> : <SignedIn session={session} activeId={searchParams.sub ?? null} />}
      </main>

      <footer className="site-footer">
        <p>
          Deliveries retry with backoff and dead-letter after repeated failure. Back to the{" "}
          <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}

function LoginCta() {
  return (
    <div className="login-cta">
      <span className="login-cta__icon">
        <LockIcon />
      </span>
      <h2 className="login-cta__title">Sign in to manage webhooks</h2>
      <p className="login-cta__body">
        Webhooks are scoped to your organization — authenticate with SMSONE to subscribe endpoints and
        inspect deliveries.
      </p>
      <a className="btn btn--primary" href="/api/auth/login">
        Sign in
      </a>
    </div>
  );
}

async function SignedIn({ session, activeId }: { session: Session; activeId: string | null }) {
  const { orgId, error: orgError } = await getActiveOrg(session.token);
  if (!orgId) {
    return (
      <div className="state" role="alert">
        <p className="state__body">{orgError ?? "Your account has no active organization."}</p>
      </div>
    );
  }

  const [{ webhooks, error }, { types }] = await Promise.all([
    listWebhooks(session.token, orgId),
    listEventTypes(session.token)
  ]);

  if (error) {
    return (
      <div className="state" role="alert">
        <p className="state__body">
          {error} Managing webhooks needs the <code>webhook:manage</code> permission in your organization.
        </p>
      </div>
    );
  }

  const active = activeId && webhooks.some((w) => w.id === activeId) ? activeId : null;

  return (
    <>
      <Webhooks webhooks={webhooks} eventTypes={types} activeId={active} />
      {active && <DeliveriesPanel session={session} orgId={orgId} webhookId={active} />}
    </>
  );
}

async function DeliveriesPanel({
  session,
  orgId,
  webhookId
}: {
  session: Session;
  orgId: string;
  webhookId: string;
}) {
  const { deliveries, error } = await listDeliveries(session.token, orgId, webhookId);

  return (
    <section style={{ marginTop: "1rem" }}>
      <h2 style={{ fontSize: "1rem", marginBottom: 8 }}>Recent deliveries</h2>
      {error ? (
        <div className="state" role="alert">
          <p className="state__body">{error}</p>
        </div>
      ) : deliveries.length === 0 ? (
        <p className="field__hint">Nothing delivered yet — fire a subscribed event and it lands here.</p>
      ) : (
        <div className="panel panel__scroll">
          <table className="keys-table">
            <thead>
              <tr>
                <th>Event</th>
                <th>Status</th>
                <th>Attempts</th>
                <th>Response</th>
                <th>Last error</th>
              </tr>
            </thead>
            <tbody>
              {deliveries.map((d: Delivery) => (
                <tr key={d.id}>
                  <td className="mono">{d.eventType}</td>
                  <td>
                    <span className={`badge badge--${d.status === "DELIVERED" ? "published" : d.status === "PENDING" ? "deprecated" : "retired"}`}>
                      {d.status}
                    </span>
                  </td>
                  <td>
                    {d.attempts}/{d.maxAttempts}
                  </td>
                  <td>{d.responseStatus ?? "—"}</td>
                  <td className="field__hint" style={{ maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis" }}>
                    {d.lastError ?? "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
