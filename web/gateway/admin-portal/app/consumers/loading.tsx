import { SkeletonTable } from "../components/Skeleton";
import { PageIntro } from "../components/Ui";

export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro
        title="Consumers"
        lede="Live plan-quota windows per consumer (organization), top talkers first — read from the gateway's usage endpoint."
      />
      <SkeletonTable rows={5} cols={6} />
    </main>
  );
}
