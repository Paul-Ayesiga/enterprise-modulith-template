import { Skeleton } from "../components/Skeleton";

export default function Loading() {
  return (
    <main id="main" className="main">
      <section className="intro">
        <h1 className="intro__title">Usage</h1>
        <p className="intro__lede">
          Your organization&rsquo;s live standing at the edge — the quota window the gateway is enforcing right
          now, and what your plan allows.
        </p>
      </section>
      <div
        style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 14, marginTop: 4 }}
        aria-hidden="true"
      >
        {Array.from({ length: 3 }).map((_, i) => (
          <div className="panel" key={i} style={{ padding: 18, display: "grid", gap: 10 }}>
            <Skeleton width="60%" height={24} />
            <Skeleton width="80%" height={12} />
          </div>
        ))}
      </div>
      <div className="panel" style={{ padding: 18, marginTop: 16 }} aria-hidden="true">
        <Skeleton width="100%" height={14} />
      </div>
    </main>
  );
}
