import { Skeleton, SkeletonLines } from "../components/Skeleton";
import { PageIntro } from "../components/Ui";

export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro
        title="IP controls"
        lede="The edge allow / deny list — refused before auth, quotas, and routing — plus dynamic auto-blocking of abusive sources."
      />
      <div className="panel" style={{ padding: "0.9rem 1.1rem", display: "grid", gap: 12 }}>
        <SkeletonLines lines={2} />
        <Skeleton height={38} />
        <Skeleton width={140} height={38} />
      </div>
    </main>
  );
}
