import { Catalog } from "./components/Catalog";
import { Header } from "./components/Header";
import { EmptyState, ErrorState } from "./components/States";
import { adminBaseUrl, fetchCatalog, openApiUrl, routeTableUrl, totalRoutes } from "./lib/gateway";

// Read the live catalog from the gateway on every request.
export const dynamic = "force-dynamic";

export default async function Home() {
  const { products, error } = await fetchCatalog();
  const routeCount = totalRoutes(products);

  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">APIs through the gateway</h1>
          <p className="intro__lede">
            Every route the edge exposes, grouped by product — with its paths, lifecycle, and whether it
            needs a token. This portal reads the gateway live and stores nothing.
          </p>
          {!error && products.length > 0 && (
            <p className="intro__stats">
              <strong>{products.length}</strong> product{products.length === 1 ? "" : "s"}
              <span className="intro__dot" aria-hidden="true">
                ·
              </span>
              <strong>{routeCount}</strong> route{routeCount === 1 ? "" : "s"}
            </p>
          )}
        </section>

        {error ? (
          <ErrorState message={error} />
        ) : products.length === 0 ? (
          <EmptyState />
        ) : (
          <Catalog products={products} />
        )}
      </main>

      <footer className="site-footer">
        <p>
          Live from <code>{adminBaseUrl()}/actuator/gatewaycatalog</code>. Read-only; nothing is stored.
        </p>
      </footer>
    </>
  );
}
