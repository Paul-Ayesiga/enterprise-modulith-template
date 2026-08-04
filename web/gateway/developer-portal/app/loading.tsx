import { Skeleton } from "./components/Skeleton";

// Catalog Suspense fallback — the intro paints instantly, product cards shimmer in.
export default function Loading() {
  return (
    <main id="main" className="main">
      <section className="intro">
        <h1 className="intro__title">APIs through the gateway</h1>
        <p className="intro__lede">
          Every route the edge exposes, grouped by product — with its paths, lifecycle, and whether it needs a
          token. This portal reads the gateway live and stores nothing.
        </p>
      </section>
      <div
        style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 16, marginTop: 20 }}
        aria-hidden="true"
      >
        {Array.from({ length: 4 }).map((_, i) => (
          <div className="panel" key={i} style={{ padding: 18, display: "grid", gap: 12 }}>
            <Skeleton width="55%" height={16} />
            <Skeleton width="80%" height={12} />
            <Skeleton width="100%" height={12} />
            <Skeleton width="40%" height={22} radius={999} />
          </div>
        ))}
      </div>
    </main>
  );
}
