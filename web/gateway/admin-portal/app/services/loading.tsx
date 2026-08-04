import { SkeletonTable } from "../components/Skeleton";
import { PageIntro } from "../components/Ui";

export default function Loading() {
  return (
    <main id="main" className="main">
      <PageIntro title="Services" lede="The backends the routes target, with how many routes point at each." />
      <SkeletonTable rows={5} cols={2} />
    </main>
  );
}
