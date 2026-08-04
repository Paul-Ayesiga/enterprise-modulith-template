"use client";

import Link from "next/link";
import { useMemo, useState } from "react";
import type { Endpoint } from "../lib/gateway";

/** A slug for a carved route id, derived from the endpoint path. */
function carveId(path: string): string {
  const slug = path
    .replace(/^\/api\/v1\//, "")
    .replace(/\{[^}]+\}/g, "")
    .replace(/[^a-zA-Z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .toLowerCase();
  return `${slug || "endpoint"}-api`;
}

/**
 * The whole API surface, and how the gateway's coarse routes cover it. Search by path/summary/method,
 * group by the serving route (see exactly what each route bundles) or by product tag, and filter to
 * the endpoints that fall to the catch-all — the ones you can't control without carving a route.
 * "Carve out a route" jumps to Register pre-filled with that exact path at a winning order.
 */
export function EndpointsExplorer({
  endpoints,
  total,
  routed
}: {
  endpoints: Endpoint[];
  total: number;
  routed: number;
}) {
  const [query, setQuery] = useState("");
  const [groupBy, setGroupBy] = useState<"route" | "tag">("route");
  const [onlyCatchall, setOnlyCatchall] = useState(false);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return endpoints.filter((e) => {
      if (onlyCatchall && !(e.route === null || e.route.id === "platform-api")) return false;
      if (!q) return true;
      return (
        e.path.toLowerCase().includes(q) ||
        e.summary.toLowerCase().includes(q) ||
        e.method.toLowerCase().includes(q) ||
        (e.route?.id ?? "").toLowerCase().includes(q)
      );
    });
  }, [endpoints, query, onlyCatchall]);

  const groups = useMemo(() => {
    const map = new Map<string, Endpoint[]>();
    for (const e of filtered) {
      const key = groupBy === "route" ? e.route?.id ?? "— no route (not exposed) —" : e.tag;
      const list = map.get(key) ?? [];
      list.push(e);
      map.set(key, list);
    }
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [filtered, groupBy]);

  const catchall = endpoints.filter((e) => e.route === null || e.route.id === "platform-api").length;

  return (
    <>
      <div className="explorer__bar">
        <input
          className="explorer__search"
          type="search"
          placeholder="Filter by path, method, summary, or route…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Filter endpoints"
        />
        <div className="explorer__toggle" role="group" aria-label="Group by">
          <button
            type="button"
            className={`chip${groupBy === "route" ? " is-on" : ""}`}
            onClick={() => setGroupBy("route")}
          >
            by route
          </button>
          <button
            type="button"
            className={`chip${groupBy === "tag" ? " is-on" : ""}`}
            onClick={() => setGroupBy("tag")}
          >
            by product
          </button>
        </div>
        <label className="explorer__filter">
          <input type="checkbox" checked={onlyCatchall} onChange={(e) => setOnlyCatchall(e.target.checked)} />
          only catch-all ({catchall})
        </label>
      </div>

      <p className="field__hint" style={{ margin: "0 0 14px" }}>
        {filtered.length} of {total} endpoints · {routed} served by a dedicated route · {catchall} fall to the
        <code> platform-api </code> catch-all. Carving a route for one gives it its own access + lifecycle.
      </p>

      {groups.map(([key, list]) => (
        <section key={key} className="epgroup">
          <div className="epgroup__head">
            <span className="epgroup__name mono">{key}</span>
            {groupBy === "route" && list[0]?.route && (
              <span className={`badge badge--${list[0].route.lifecycle.toLowerCase()}`}>
                {lifecycleLabel(list[0].route.lifecycle)}
              </span>
            )}
            <span className="epgroup__count">{list.length}</span>
          </div>
          <div className="panel panel__scroll">
            <table className="table">
              <thead>
                <tr>
                  <th>Method</th>
                  <th>Path</th>
                  <th>Summary</th>
                  {groupBy === "tag" && <th>Served by</th>}
                  <th>
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {list.map((e) => (
                  <tr key={`${e.method} ${e.path}`}>
                    <td>
                      <span className={`method method--${e.method.toLowerCase()}`}>{e.method}</span>
                    </td>
                    <td className="mono">{e.path}</td>
                    <td className="epsummary">{e.summary || "—"}</td>
                    {groupBy === "tag" && (
                      <td className="mono num">{e.route ? e.route.id : "— none —"}</td>
                    )}
                    <td style={{ textAlign: "right" }}>
                      <Link
                        className="btn btn--ghost btn--sm"
                        href={`/routes?carvePath=${encodeURIComponent(e.path)}&carveId=${carveId(
                          e.path
                        )}&carveOrder=5`}
                      >
                        Carve out
                      </Link>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ))}

      {groups.length === 0 && (
        <div className="panel" style={{ padding: "0.9rem 1.1rem" }}>
          <p className="field__hint" style={{ margin: 0 }}>
            No endpoints match “{query}”.
          </p>
        </div>
      )}
    </>
  );
}

function lifecycleLabel(lifecycle: string): string {
  const key = lifecycle.toUpperCase();
  if (key === "RETIRED") return "Retired";
  if (key === "DEPRECATED") return "Deprecated";
  return "Published";
}
