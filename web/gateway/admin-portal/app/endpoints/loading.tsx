import { Skeleton, SkeletonTable } from "../components/Skeleton";
import { PageIntro } from "../components/Ui";

export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro
        title="Endpoints"
        lede="Every operation the platform documents, and the gateway route that serves each one today. This is the whole surface behind the coarse routes — an endpoint with no dedicated route falls to the catch-all, so carve one out to control it on its own."
      />
      <div className="explorer__bar">
        <Skeleton height={38} width="60%" />
        <Skeleton height={38} width={160} />
      </div>
      <div style={{ marginTop: 20, display: "grid", gap: 8 }}>
        <Skeleton width={160} height={16} />
      </div>
      <div style={{ marginTop: 10 }}>
        <SkeletonTable rows={5} cols={4} />
      </div>
    </main>
  );
}
