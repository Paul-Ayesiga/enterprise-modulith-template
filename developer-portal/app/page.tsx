import { fetchCatalog, openApiUrl, adminBaseUrl, type Product, type RouteSummary } from "./lib/gateway";

// Always read the live catalog from the gateway on each request.
export const dynamic = "force-dynamic";

export default async function Home() {
  const { products, error } = await fetchCatalog();

  return (
    <main>
      <section className="hero">
        <h1>SMSOne Developer Portal</h1>
        <p>The APIs available through the gateway — grouped by product, with lifecycle and access.</p>
        <div className="links">
          <a className="button" href={openApiUrl()} target="_blank" rel="noreferrer">OpenAPI document ↗</a>
          <a className="button" href={`${adminBaseUrl()}/actuator/gatewayroutes`} target="_blank" rel="noreferrer">
            Route table ↗
          </a>
        </div>
      </section>

      {error && (
        <div className="error">
          Could not reach the gateway at <code>{adminBaseUrl()}</code> — <code>{error}</code>.
          <br />
          Start it with <code>make gateway</code> (and <code>make run</code> for the modulith), or set{" "}
          <code>GATEWAY_ADMIN_URL</code>.
        </div>
      )}

      {!error && products.length === 0 && (
        <div className="error">No products are published yet. Give a route a <code>product</code> in the gateway config.</div>
      )}

      {products.map((product) => (
        <ProductCard key={product.id} product={product} />
      ))}

      <footer>
        Live from <code>{adminBaseUrl()}/actuator/gatewaycatalog</code>. This portal reads the gateway; it stores nothing.
      </footer>
    </main>
  );
}

function ProductCard({ product }: { product: Product }) {
  return (
    <section className="product">
      <h2>{product.name}</h2>
      {product.description && <p className="desc">{product.description}</p>}
      {product.routes.map((route) => (
        <RouteRow key={route.id} route={route} />
      ))}
    </section>
  );
}

function RouteRow({ route }: { route: RouteSummary }) {
  const lifecycle = route.lifecycle.toLowerCase();
  return (
    <div className="route">
      <span className="path">{route.paths.join(", ") || "—"}</span>
      <span className="rid">{route.id}</span>
      <span className="spacer" />
      {route.authenticated ? <span className="badge auth">token</span> : <span className="badge open">open</span>}
      <span className={`badge ${lifecycle}`}>{lifecycle}</span>
    </div>
  );
}
