import { Skeleton, SkeletonTable } from "../components/Skeleton";

export default function Loading() {
  return (
    <main id="main" className="main">
      <section className="intro">
        <h1 className="intro__title">Credentials</h1>
        <p className="intro__lede">
          Manage your organization&rsquo;s API keys — for server-to-server calls with an <code>X-Api-Key</code>{" "}
          header, no interactive login.
        </p>
      </section>
      <section className="section">
        <h2 className="section__title">New API key</h2>
        <div className="panel" style={{ padding: "18px 20px", display: "grid", gap: 14 }}>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
            <Skeleton height={38} />
            <Skeleton height={38} />
          </div>
          <Skeleton width={130} height={38} />
        </div>
      </section>
      <section className="section">
        <h2 className="section__title">Your keys</h2>
        <SkeletonTable rows={4} cols={5} />
      </section>
    </main>
  );
}
