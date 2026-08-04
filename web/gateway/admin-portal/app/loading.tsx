import { Skeleton } from "./components/Skeleton";
import { PageIntro } from "./components/Ui";

// Overview Suspense fallback: heading paints instantly, the stat + jump cards shimmer in.
export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro
        title="Gateway control plane"
        lede="The live route table, the endpoints behind it, consumers, and edge IP controls — read straight from the gateway's admin endpoints. A change you make here takes effect on the edge immediately, with no restart."
      />
      <section className="stats" aria-hidden="true">
        {Array.from({ length: 4 }).map((_, i) => (
          <div className="stat" key={i} style={{ display: "grid", gap: 10 }}>
            <Skeleton width="55%" height={26} />
            <Skeleton width="70%" height={12} />
          </div>
        ))}
      </section>
      <section className="section">
        <div className="section__head">
          <h2 className="section__title">Jump to</h2>
        </div>
        <div className="quicklinks" aria-hidden="true">
          {Array.from({ length: 5 }).map((_, i) => (
            <div className="quicklink" key={i} style={{ display: "grid", gap: 8 }}>
              <Skeleton width="45%" height={15} />
              <Skeleton width="85%" height={12} />
            </div>
          ))}
        </div>
      </section>
    </main>
  );
}
