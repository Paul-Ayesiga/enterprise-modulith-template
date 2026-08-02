import Link from "next/link";
import { notFound } from "next/navigation";
import { Header } from "../../components/Header";
import { PolicyView } from "../../components/PolicyView";
import { fetchCatalog, openApiUrl, routeTableUrl, type RouteSummary } from "../../lib/gateway";

export const dynamic = "force-dynamic";

export default async function ProductPage({ params }: { params: { id: string } }) {
  const id = decodeURIComponent(params.id);
  const { products, error } = await fetchCatalog();
  const product = products.find((p) => p.id === id);

  if (error) {
    return (
      <>
        <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />
        <main id="main" className="main">
          <div className="state state--inline">
            <p className="state__body">Couldn&rsquo;t load the catalog: {error}</p>
          </div>
        </main>
      </>
    );
  }
  if (!product) {
    notFound();
  }

  return (
    <>
      <Header openApiUrl={openApiUrl()} routeTableUrl={routeTableUrl()} />

      <main id="main" className="main">
        <nav className="crumbs" aria-label="Breadcrumb">
          <Link href="/">APIs</Link>
          <span className="crumbs__sep" aria-hidden="true">
            /
          </span>
          <span aria-current="page">{product.name}</span>
        </nav>

        <section className="intro">
          <h1 className="intro__title">{product.name}</h1>
          {product.description && <p className="intro__lede">{product.description}</p>}
          <p className="intro__stats">
            <strong>{product.routes.length}</strong> route{product.routes.length === 1 ? "" : "s"}
          </p>
        </section>

        <ul className="route-list" role="list">
          {product.routes.map((route) => (
            <li key={route.id}>
              <RouteDetail route={route} />
            </li>
          ))}
        </ul>
      </main>

      <footer className="site-footer">
        <p>
          Back to the <Link href="/">API catalog</Link>. Live from the gateway.
        </p>
      </footer>
    </>
  );
}

function RouteDetail({ route }: { route: RouteSummary }) {
  const first = examplePath(route.paths[0] ?? "/");
  return (
    <section className="route-detail" aria-labelledby={`r-${route.id}`}>
      <div className="route-detail__head">
        <h2 className="route-detail__id" id={`r-${route.id}`}>
          {route.id}
        </h2>
        <Link className="route-try" href={`/try?path=${encodeURIComponent(first)}&method=GET`}>
          Try
        </Link>
      </div>
      <div className="paths route-detail__paths">
        {route.paths.length > 0 ? (
          route.paths.map((p) => <code key={p}>{p}</code>)
        ) : (
          <span className="field__hint">—</span>
        )}
      </div>
      <PolicyView route={route} />
    </section>
  );
}

/** A route pattern turned into a concrete example path (drop the trailing glob). */
function examplePath(pattern: string): string {
  return pattern.replace(/\/\*\*$/, "").replace(/\/\*$/, "") || "/";
}
