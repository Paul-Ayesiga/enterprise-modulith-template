import Link from "next/link";
import { LockIcon } from "../components/Icons";
import { getSession, type Session } from "../lib/auth";
import { getActiveOrg } from "../lib/credentials";
import { fetchSubscription, fetchUsageFor, type MyUsage } from "../lib/usage";

export const dynamic = "force-dynamic";

export default function UsagePage() {
  const session = getSession();

  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Usage</h1>
          <p className="intro__lede">
            Your organization&rsquo;s live standing at the edge — the quota window the gateway is
            enforcing right now, and what your plan allows.
          </p>
        </section>

        {!session ? <LoginCta /> : <SignedIn session={session} />}
      </main>

      <footer className="site-footer">
        <p>
          Usage is metered per organization at the gateway. Back to the <Link href="/">API catalog</Link>.
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
      <h2 className="login-cta__title">Sign in to see your usage</h2>
      <p className="login-cta__body">
        Usage is scoped to your organization — authenticate with SMSONE to see your quota window,
        plan, and entitlements.
      </p>
      <a className="btn btn--primary" href="/api/auth/login">
        Sign in
      </a>
    </div>
  );
}

async function SignedIn({ session }: { session: Session }) {
  const { orgId, error: orgError } = await getActiveOrg(session.token);
  if (!orgId) {
    return (
      <div className="state" role="alert">
        <p className="state__body">{orgError ?? "Your account has no active organization."}</p>
      </div>
    );
  }

  const [{ usage, error: usageError }, { sub, error: subError }] = await Promise.all([
    fetchUsageFor(orgId),
    fetchSubscription(session.token, orgId)
  ]);

  return (
    <>
      {sub && (
        <section className="panel" style={{ padding: "1rem 1.25rem", marginBottom: "1rem" }}>
          <div className="policy__row">
            <span className="policy__key">Plan</span>
            <span className="policy__val">
              <strong>{sub.planName}</strong> <span className="mono">({sub.planCode})</span>
            </span>
          </div>
          <div className="policy__row">
            <span className="policy__key">Status</span>
            <span className="policy__val">
              <span className={`badge badge--${sub.status === "ACTIVE" || sub.status === "TRIALING" ? "published" : "retired"}`}>
                {sub.status}
              </span>
              {sub.trialEndsAt && (
                <span className="field__hint" style={{ marginLeft: 8 }}>
                  trial ends {new Date(sub.trialEndsAt).toLocaleDateString()}
                </span>
              )}
            </span>
          </div>
        </section>
      )}
      {subError && (
        <div className="state" role="alert">
          <p className="state__body">{subError}</p>
        </div>
      )}

      {usage ? (
        <QuotaCard usage={usage} />
      ) : (
        <div className="state" role="alert">
          <p className="state__body">{usageError}</p>
        </div>
      )}

      {sub && Object.keys(sub.entitlements ?? {}).length > 0 && (
        <section className="panel panel__scroll" style={{ marginTop: "1rem" }}>
          <table className="keys-table">
            <thead>
              <tr>
                <th>Entitlement</th>
                <th>Allowance</th>
              </tr>
            </thead>
            <tbody>
              {Object.entries(sub.entitlements).map(([key, value]) => (
                <tr key={key}>
                  <td className="mono">{key}</td>
                  <td>{value === null ? "Included" : value.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <p className="field__hint" style={{ marginTop: "1rem" }}>
        Two different ceilings answer 429: the short-burst rate limit (per caller, seconds-scale — just
        back off) and this plan quota (your subscription&rsquo;s ceiling per window — raised by upgrading
        the plan). This page shows the quota.
      </p>
    </>
  );
}

function QuotaCard({ usage }: { usage: MyUsage }) {
  if (!usage.limited) {
    return (
      <section className="panel" style={{ padding: "1rem 1.25rem" }}>
        <p style={{ margin: 0 }}>
          <strong>API quota:</strong> unlimited on your plan
          {usage.used > 0 && (
            <span className="field__hint" style={{ marginLeft: 8 }}>
              {usage.used.toLocaleString()} calls this window
            </span>
          )}
        </p>
      </section>
    );
  }

  const limit = usage.limit ?? 0;
  const pct = limit > 0 ? Math.min(100, Math.round((usage.used / limit) * 100)) : 0;
  const windowLabel =
    usage.windowSeconds === 60
      ? "per minute"
      : usage.windowSeconds === 3600
        ? "per hour"
        : `per ${usage.windowSeconds}s window`;

  return (
    <section className="panel" style={{ padding: "1rem 1.25rem" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "baseline", gap: 12, flexWrap: "wrap" }}>
        <p style={{ margin: 0 }}>
          <strong>API quota:</strong> {usage.used.toLocaleString()} of {limit.toLocaleString()} {windowLabel}
        </p>
        <span className="field__hint">
          {usage.remaining?.toLocaleString()} remaining · window resets in {usage.resetSeconds}s
        </span>
      </div>
      <div
        role="progressbar"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Quota used this window"
        style={{ marginTop: 10, height: 8, borderRadius: 999, background: "var(--line, #e5e7eb)", overflow: "hidden" }}
      >
        <div
          style={{
            width: `${pct}%`,
            height: "100%",
            borderRadius: 999,
            background: pct >= 90 ? "var(--danger, #b91c1c)" : "var(--accent, #10218B)"
          }}
        />
      </div>
      <p className="field__hint" style={{ marginTop: 8 }}>
        Counts attempts this window, including denied ones. Over the ceiling the edge answers 429 until
        the window resets.
      </p>
    </section>
  );
}
