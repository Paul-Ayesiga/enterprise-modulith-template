import { CreateRouteForm } from "../components/CreateRouteForm";
import { RouteTableRow } from "../components/RouteTableRow";
import { GatewayUnreachable, PageIntro } from "../components/Ui";
import { adminBaseUrl, fetchOverview } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function RoutesPage({
  searchParams
}: {
  searchParams: { carvePath?: string; carveId?: string; carveOrder?: string };
}) {
  const { routes, services, error } = await fetchOverview();
  const serviceIds = services.map((s) => s.id);

  return (
    <main id="main" className="main">
      <PageIntro
        title="Routes"
        lede="The live edge route table. Register a new route, edit one in place, or move it through its lifecycle. Lower order wins when paths overlap — so a specific route out-ranks a coarse product group."
      />
      {error ? (
        <GatewayUnreachable baseUrl={adminBaseUrl()} error={error} />
      ) : (
        <>
          <section className="section">
            <div className="section__head">
              <h2 className="section__title">Register a route</h2>
              <span className="section__hint">POST → gatewayroutes · live immediately</span>
            </div>
            <div className="panel">
              <CreateRouteForm
                services={serviceIds}
                defaultId={searchParams.carveId}
                defaultPath={searchParams.carvePath}
                defaultOrder={searchParams.carveOrder}
              />
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
                    <RouteTableRow key={route.id} route={route} services={serviceIds} />
                  ))}
                </tbody>
              </table>
            </div>
          </section>
        </>
      )}
    </main>
  );
}
