import { GatewayUnreachable, PageIntro } from "../components/Ui";
import { adminBaseUrl, fetchOverview } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function ServicesPage() {
  const { services, error } = await fetchOverview();

  return (
    <main id="main" className="main">
      <PageIntro title="Services" lede="The backends the routes target, with how many routes point at each." />
      {error ? (
        <GatewayUnreachable baseUrl={adminBaseUrl()} error={error} />
      ) : (
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
      )}
    </main>
  );
}
