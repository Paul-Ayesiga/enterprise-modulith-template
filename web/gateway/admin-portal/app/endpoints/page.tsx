import { EndpointsExplorer } from "../components/EndpointsExplorer";
import { PageIntro } from "../components/Ui";
import { docsBaseUrl, fetchEndpoints } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function EndpointsPage() {
  const { endpoints, total, routed, error } = await fetchEndpoints();

  return (
    <main id="main" className="main">
      <PageIntro
        title="Endpoints"
        lede="Every operation the platform documents, and the gateway route that serves each one today. This is the whole surface behind the coarse routes — an endpoint with no dedicated route falls to the catch-all, so carve one out to control it on its own."
      />
      {error ? (
        <div className="state state--error" role="alert">
          <p className="state__title">Couldn&rsquo;t load the API surface</p>
          <p className="state__body">
            Tried the modulith OpenAPI at <code>{docsBaseUrl()}/v3/api-docs</code>. Start it with{" "}
            <code>make run</code>, or point <code>MODULITH_DOCS_URL</code> at it. ({error})
          </p>
        </div>
      ) : (
        <EndpointsExplorer endpoints={endpoints} total={total} routed={routed} />
      )}
    </main>
  );
}
