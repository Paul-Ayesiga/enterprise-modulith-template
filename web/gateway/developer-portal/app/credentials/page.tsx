import Link from "next/link";
import { ApiKeys } from "../components/ApiKeys";
import { LockIcon } from "../components/Icons";
import { getSession, type Session } from "../lib/auth";
import { getActiveOrg, listKeys } from "../lib/credentials";

export const dynamic = "force-dynamic";

export default async function CredentialsPage({ searchParams }: { searchParams: { error?: string } }) {
  const session = getSession();

  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Credentials</h1>
          <p className="intro__lede">
            Manage your organization&rsquo;s API keys — for server-to-server calls with an{" "}
            <code>X-Api-Key</code> header, no interactive login.
          </p>
        </section>

        {searchParams.error && (
          <div className="state" role="alert">
            <p className="state__body">{searchParams.error}</p>
          </div>
        )}

        {!session ? <LoginCta /> : <SignedIn session={session} />}
      </main>

      <footer className="site-footer">
        <p>
          Keys are scoped to your active organization. Back to the <Link href="/">API catalog</Link>.
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
      <h2 className="login-cta__title">Sign in to manage keys</h2>
      <p className="login-cta__body">
        Authenticate with SMSOne to create, rotate, and revoke API keys for your organization.
      </p>
      <a className="btn btn--primary" href="/api/auth/login">
        Sign in
      </a>
    </div>
  );
}

async function SignedIn({ session }: { session: Session }) {
  const { orgId, error } = await getActiveOrg(session.token);

  return (
    <>
      <div className="signed-in">
        <span className="signed-in__who">
          Signed in as <strong>{session.name ?? session.email ?? session.sub}</strong>
        </span>
        <a className="btn btn--sm btn--ghost" href="/api/auth/logout">
          Sign out
        </a>
      </div>

      {!orgId ? (
        <div className="state" role="alert">
          <p className="state__body">
            {error ??
              "Your token has no active organization. Sign in with the 'organization' scope, or join an org first."}
          </p>
        </div>
      ) : (
        <KeysSection token={session.token} orgId={orgId} />
      )}
    </>
  );
}

async function KeysSection({ token, orgId }: { token: string; orgId: string }) {
  const { keys, error } = await listKeys(token, orgId);
  if (error) {
    return (
      <div className="state" role="alert">
        <p className="state__body">{error}</p>
      </div>
    );
  }
  return <ApiKeys keys={keys} />;
}
