import Link from "next/link";
import { GatewayUnreachable, PageIntro, Stat } from "./components/Ui";
import { adminBaseUrl, fetchOverview } from "./lib/gateway";

export const dynamic = "force-dynamic";

const LINKS: [string, string, string][] = [
  ["/routes", "Routes", "register, edit, and move routes through their lifecycle"],
  ["/endpoints", "Endpoints", "every API operation and the route serving it today"],
  ["/consumers", "Consumers", "live quota windows, top talkers first"],
  ["/ip-controls", "IP controls", "allow / deny lists and dynamic auto-blocking"],
  ["/services", "Services", "the backends the routes target"]
];

export default async function Overview() {
  const { routes, services, productCount, health, error } = await fetchOverview();

  return (
    <main id="main" className="main">
      <PageIntro
        title="Gateway control plane"
        lede="The live route table, the endpoints behind it, consumers, and edge IP controls — read straight from the gateway's admin endpoints. A change you make here takes effect on the edge immediately, with no restart."
      />
      {error ? (
        <GatewayUnreachable baseUrl={adminBaseUrl()} error={error} />
      ) : (
        <>
          <section className="stats" aria-label="Overview">
            <Stat value={routes.length} label={`Route${routes.length === 1 ? "" : "s"}`} accent />
            <Stat value={services.length} label={`Service${services.length === 1 ? "" : "s"}`} />
            <Stat value={productCount} label={`Product${productCount === 1 ? "" : "s"}`} />
            <Stat value={health.toUpperCase() === "UP" ? "Healthy" : health} label="Edge health" />
          </section>

          <section className="section">
            <div className="section__head">
              <h2 className="section__title">Jump to</h2>
            </div>
            <div className="quicklinks">
              {LINKS.map(([href, title, desc]) => (
                <Link key={href} href={href} className="quicklink">
                  <span className="quicklink__title">{title}</span>
                  <span className="quicklink__desc">{desc}</span>
                </Link>
              ))}
            </div>
          </section>
        </>
      )}
    </main>
  );
}
