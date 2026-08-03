import { CreateRouteForm } from "./components/CreateRouteForm";
import { Header } from "./components/Header";
import { RouteTableRow } from "./components/RouteTableRow";
import { adminBaseUrl, fetchOverview, grafanaUrl } from "./lib/gateway";

// Read the live gateway state on every request.
export const dynamic = "force-dynamic";

export default async function AdminHome() {
  const { routes, services, productCount, health, error } = await fetchOverview();
  const routesUrl = `${adminBaseUrl()}/actuator/gatewayroutes`;

  return (
    <>
      <Header health={health} grafanaUrl={grafanaUrl()} routesUrl={routesUrl} />

      <main id="main" className="main">
        <section className="intro">
          <h1 className="intro__title">Gateway control plane</h1>
          <p className="intro__lede">
            The live route table, the services behind it, and the API catalog — read straight from the
            gateway&rsquo;s admin endpoints. A route you register or delete here takes effect on the edge
            immediately, with no restart.
          </p>
        </section>

        {error ? (
          <div className="state state--error" role="alert">
            <p className="state__title">Can&rsquo;t reach the gateway admin API</p>
            <p className="state__body">
              Tried <code>{adminBaseUrl()}</code>. Start the edge with <code>make gateway</code>, or point{" "}
              <code>GATEWAY_ADMIN_URL</code> at it. ({error})
            </p>
          </div>
        ) : (
          <>
            <section className="stats" aria-label="Overview">
              <Stat value={routes.length} label={`Route${routes.length === 1 ? "" : "s"}`} />
              <Stat value={services.length} label={`Service${services.length === 1 ? "" : "s"}`} />
              <Stat value={productCount} label={`Product${productCount === 1 ? "" : "s"}`} />
              <Stat value={health.toUpperCase() === "UP" ? "Healthy" : health} label="Edge health" />
            </section>

            <section className="section">
              <div className="section__head">
                <h2 className="section__title">Register a route</h2>
                <span className="section__hint">POST → gatewayroutes · live immediately</span>
              </div>
              <div className="panel">
                <CreateRouteForm services={services.map((s) => s.id)} />
              </div>
            </section>

            <section className="section">
              <div className="section__head">
                <h2 className="section__title">Routes</h2>
                <span className="section__hint">sorted by order — lower wins</span>
              </div>
              <div className="panel panel__scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Route</th>
                      <th>Order</th>
                      <th>Service</th>
                      <th>Paths</th>
                      <th>Access</th>
                      <th>Lifecycle</th>
                      <th>
                        <span className="visually-hidden">Actions</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {routes.map((route) => (
                      <RouteTableRow key={route.id} route={route} services={services.map((s) => s.id)} />
                    ))}
                  </tbody>
                </table>
              </div>
            </section>

            <section className="section">
              <div className="section__head">
                <h2 className="section__title">Services</h2>
                <span className="section__hint">backends the routes target</span>
              </div>
              <div className="panel panel__scroll">
                <table className="table">
                  <thead>
                    <tr>
                      <th>Service</th>
                      <th>Routes</th>
                    </tr>
                  </thead>
                  <tbody>
                    {services.map((s) => (
                      <tr key={s.id}>
                        <td className="mono route-id">{s.id}</td>
                        <td className="num">{s.routeCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          </>
        )}
      </main>

      <footer className="site-footer">
        Live from <code>{routesUrl}</code>. This portal talks to the gateway&rsquo;s admin port only —
        keep it off the public network.
      </footer>
    </>
  );
}

function Stat({ value, label }: { value: number | string; label: string }) {
  return (
    <div className="stat">
      <div className="stat__value">{value}</div>
      <div className="stat__label">{label}</div>
    </div>
  );
}

