import { PageIntro } from "../components/Ui";
import { fetchUsage, type UsageRow } from "../lib/gateway";

export const dynamic = "force-dynamic";

export default async function ConsumersPage() {
  const { usage, error } = await fetchUsage();

  return (
    <main id="main" className="main">
      <PageIntro
        title="Consumers"
        lede="Live plan-quota windows per consumer (organization), top talkers first — read from the gateway's usage endpoint."
      />
      {usage.length === 0 ? (
        <div className="panel" style={{ padding: "0.9rem 1.1rem" }}>
          <p className="field__hint" style={{ margin: 0 }}>
            {error
              ? `Usage unavailable: ${error}`
              : "No metered traffic this window — consumers appear here as soon as an authenticated, quota-limited call passes the edge."}
          </p>
        </div>
      ) : (
        <div className="panel panel__scroll">
          <table className="table">
            <thead>
              <tr>
                <th>Consumer (org)</th>
                <th>Used</th>
                <th>Limit</th>
                <th>Remaining</th>
                <th>Window</th>
                <th>Resets in</th>
              </tr>
            </thead>
            <tbody>
              {usage.map((row) => (
                <UsageRowView key={row.consumer} row={row} />
              ))}
            </tbody>
          </table>
        </div>
      )}
    </main>
  );
}

function UsageRowView({ row }: { row: UsageRow }) {
  const exhausted = row.limited && (row.remaining ?? 0) <= 0;
  return (
    <tr>
      <td className="mono route-id">{row.consumer}</td>
      <td className="num">{row.used.toLocaleString()}</td>
      <td className="num">{row.limited ? row.limit?.toLocaleString() : "∞"}</td>
      <td className="num">
        {row.limited ? (
          <span className={exhausted ? "badge badge--retired" : undefined}>
            {row.remaining?.toLocaleString()}
          </span>
        ) : (
          "—"
        )}
      </td>
      <td>{row.limited ? `${row.windowSeconds}s` : "—"}</td>
      <td className="num">{row.resetSeconds}s</td>
    </tr>
  );
}
