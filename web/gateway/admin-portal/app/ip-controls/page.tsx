import { BlocklistPanel } from "../components/BlocklistPanel";
import { PageIntro } from "../components/Ui";
import { fetchBlocklist } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function IpControlsPage() {
  const blocklist = await fetchBlocklist();

  return (
    <main id="main" className="main">
      <PageIntro
        title="IP controls"
        lede="The edge allow / deny list — refused before auth, quotas, and routing — plus dynamic auto-blocking of abusive sources."
      >
        <p className="intro__stats" style={{ marginTop: 8, color: "var(--text-faint)", fontSize: "0.82rem" }}>
          {blocklist.autoBlock.enabled ? "auto-blocking on" : "manual only"} · trusted proxy hops:{" "}
          {blocklist.trustedProxyHops}
        </p>
      </PageIntro>
      <BlocklistPanel
        entries={blocklist.entries}
        allow={blocklist.allow}
        autoBlock={blocklist.autoBlock}
        persistentEnabled={blocklist.persistentEnabled}
        error={blocklist.error}
      />
    </main>
  );
}
