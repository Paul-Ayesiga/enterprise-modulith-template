import Link from "next/link";
import { Header } from "../components/Header";
import { TryConsole } from "../components/TryConsole";
import { openApiUrl, routeTableUrl } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default function TryPage({
  searchParams
}: {
  searchParams: { path?: string; method?: string };
}) {
  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Try it</h1>
          <p className="intro__lede">
            Send a live request through the gateway — choose a method, paste a token, and read the
            response. It&rsquo;s proxied server-side, so there&rsquo;s no CORS and the token stays off the page.
          </p>
        </section>

        <TryConsole
          initialPath={searchParams.path ?? "/api/v1/settings"}
          initialMethod={searchParams.method ?? "GET"}
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
