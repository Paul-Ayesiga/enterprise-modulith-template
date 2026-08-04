import Link from "next/link";
import { TryConsole } from "../components/TryConsole";
import { getSession } from "../lib/auth";

export const dynamic = "force-dynamic";

export default function TryPage({
  searchParams
}: {
  searchParams: { path?: string; method?: string };
}) {
  const signedIn = getSession() !== null;
  return (
    <>

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Try it</h1>
          <p className="intro__lede">
            Send a live request through the gateway — as your signed-in session, a pasted token, or an
            API key — and read the response. It&rsquo;s proxied server-side, so there&rsquo;s no CORS and
            credentials stay off the page.
          </p>
        </section>

        <TryConsole
          initialPath={searchParams.path ?? "/api/v1/settings"}
          initialMethod={searchParams.method ?? "GET"}
          signedIn={signedIn}
        />
      </main>

      <footer className="site-footer">
        <p>
          Proxied through the gateway front door. Back to the <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}
