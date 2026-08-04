import { Skeleton, SkeletonTable } from "../components/Skeleton";
import { PageIntro } from "../components/Ui";

// Shown while the live route table loads (Suspense fallback for the server render).
export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro
        title="Routes"
        lede="The live edge route table. Register a new route, edit one in place, or move it through its lifecycle. Lower order wins when paths overlap — so a specific route out-ranks a coarse product group."
      />
      <section className="section">
        <div className="section__head">
          <h2 className="section__title">Register a route</h2>
        </div>
        <div className="panel" style={{ padding: "18px 20px", display: "grid", gap: 14 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
            <Skeleton height={38} />
            <Skeleton height={38} />
          </div>
          <Skeleton width={150} height={38} />
        </div>
      </section>
      <section className="section">
        <div className="section__head">
          <h2 className="section__title">Routes</h2>
        </div>
        <SkeletonTable rows={6} cols={6} />
      </section>
    </main>
  );
}
