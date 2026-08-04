import { Skeleton } from "../components/Skeleton";

export default function Loading() {
  return (
    <main id="main" className="main">
      <section className="intro">
        <h1 className="intro__title">API reference</h1>
        <p className="intro__lede">
          Every operation the API exposes — grouped by area, with its parameters and responses.
        </p>
      </section>
      <div style={{ display: "grid", gap: 10, marginTop: 20 }} aria-hidden="true">
        {Array.from({ length: 6 }).map((_, i) => (
          <div
            className="panel"
            key={i}
            style={{ padding: "12px 16px", display: "flex", alignItems: "center", gap: 12 }}
          >
            <Skeleton width={52} height={18} radius={7} />
            <Skeleton width={`${30 + (i % 4) * 12}%`} height={13} />
            <Skeleton width={`${20 + (i % 3) * 10}%`} height={12} />
          </div>
        ))}
      </div>
    </main>
  );
}
