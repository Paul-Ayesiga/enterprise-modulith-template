import Link from "next/link";
import { Header } from "../components/Header";
import { Reference } from "../components/Reference";
import { docsBaseUrl, fetchOpenApi, openApiUrl, routeTableUrl } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function ReferencePage() {
  const { doc, error } = await fetchOpenApi();

  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">API reference</h1>
          <p className="intro__lede">
            Every operation the API exposes{doc?.version ? ` (v${doc.version})` : ""} — grouped by area, with
            its parameters and responses. Send one from <Link href="/try">Try it</Link>.
          </p>
        </section>

        {error || !doc ? (
          <div className="state" role="alert">
            <p className="state__title">Couldn&rsquo;t load the API reference</p>
            <p className="state__body">
              Tried <code>{docsBaseUrl()}/v3/api-docs</code>. Is the app running? Point{" "}
              <code>MODULITH_DOCS_URL</code> at it if it&rsquo;s elsewhere. ({error})
            </p>
          </div>
        ) : (
          <Reference doc={doc} />
        )}
      </main>

      <footer className="site-footer">
        <p>
          Generated from the live OpenAPI. Back to the <Link href="/">API catalog</Link>.
        </p>
      </footer>
    </>
  );
}
