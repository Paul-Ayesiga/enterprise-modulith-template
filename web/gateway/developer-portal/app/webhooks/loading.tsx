import { Skeleton, SkeletonTable } from "../components/Skeleton";

export default function Loading() {
  return (
    <main id="main" className="main">
      <section className="intro">
        <h1 className="intro__title">Webhooks</h1>
        <p className="intro__lede">
          Push events to your systems as they happen — signed deliveries, automatic retries, and a
          per-subscription delivery log.
        </p>
      </section>
      <div className="panel" style={{ padding: "18px 20px", display: "grid", gap: 14, marginTop: 4 }}>
        <Skeleton height={38} />
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(240px, 1fr))", gap: 8 }}>
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} height={16} />
          ))}
        </div>
        <Skeleton width={150} height={38} />
      </div>
      <div style={{ marginTop: 16 }}>
        <SkeletonTable rows={3} cols={5} />
      </div>
    </main>
  );
}
