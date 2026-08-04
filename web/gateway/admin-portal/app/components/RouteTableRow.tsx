"use client";

import { useState, useTransition } from "react";
import { endpointsForRoute } from "../lib/actions";
import type { Endpoint, RouteRow } from "../lib/gateway";
import { ChevronIcon, GlobeIcon, LockIcon } from "./Icons";
import { EditRouteForm, RouteRowActions } from "./RouteRowActions";
import { Skeleton } from "./Skeleton";

/**
 * One route in the table. The route id is a disclosure: clicking it expands a sub-row that lists the
 * documented endpoints this route serves, fetched on demand (skeleton while in flight, cached after
 * the first open). Editing opens a second full-width sub-row beneath it.
 */
export function RouteTableRow({ route, services }: { route: RouteRow; services: string[] }) {
  const [editing, setEditing] = useState(false);
  const [expanded, setExpanded] = useState(false);
  const [endpoints, setEndpoints] = useState<Endpoint[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, startLoad] = useTransition();

  function toggleExpand() {
    const next = !expanded;
    setExpanded(next);
    // Progressive load: fetch the first time this route is opened, then serve from state on re-open.
    if (next && endpoints === null && !loading) {
      setLoadError(null);
      startLoad(async () => {
        const res = await endpointsForRoute(route.id);
        if (res.error) setLoadError(res.error);
        else setEndpoints(res.endpoints);
      });
    }
  }

  return (
    <>
      <tr className={expanded ? "is-open" : undefined}>
        <td>
          <button
            type="button"
            className="route-disclosure"
            onClick={toggleExpand}
            aria-expanded={expanded}
            aria-label={`${expanded ? "Hide" : "Show"} the endpoints ${route.id} serves`}
          >
            <span className={`route-disclosure__chevron${expanded ? " is-open" : ""}`} aria-hidden="true">
              <ChevronIcon />
            </span>
            <span className="mono route-id">{route.id}</span>
          </button>
          {route.persistent && (
            <span
              className="badge badge--published"
              style={{ marginLeft: 6, verticalAlign: "middle" }}
              title="Durable — survives a gateway restart"
            >
              durable
            </span>
          )}
          {route.product && <div className="field__hint">{route.product}</div>}
        </td>
        <td className="num">{route.order}</td>
        <td className="mono">{route.serviceId}</td>
        <td>
          <div className="paths">
            {route.paths.length ? (
              route.paths.map((p) => <code key={p}>{p}</code>)
            ) : (
              <span className="field__hint">—</span>
            )}
          </div>
        </td>
        <td>
          {route.authenticated ? (
            <span className="badge badge--auth">
              <LockIcon /> Token
            </span>
          ) : (
            <span className="badge badge--open">
              <GlobeIcon /> Open
            </span>
          )}
          {route.rateLimited && <div className="field__hint">rate-limited</div>}
        </td>
        <td>
          <LifecycleBadge lifecycle={route.lifecycle} />
        </td>
        <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
          <RouteRowActions
            route={route}
            services={services}
            editing={editing}
            onToggleEdit={() => setEditing((v) => !v)}
          />
        </td>
      </tr>
      {expanded && (
        <tr className="subrow">
          <td colSpan={7} className="subrow__cell">
            <RouteEndpoints routeId={route.id} loading={loading} endpoints={endpoints} error={loadError} />
          </td>
        </tr>
      )}
      {editing && (
        <tr className="subrow">
          <td colSpan={7} className="subrow__cell">
            <EditRouteForm route={route} services={services} onDone={() => setEditing(false)} />
          </td>
        </tr>
      )}
    </>
  );
}

/** The expanded drill-down: the endpoints this route serves, or a skeleton / empty / error state. */
function RouteEndpoints({
  routeId,
  loading,
  endpoints,
  error
}: {
  routeId: string;
  loading: boolean;
  endpoints: Endpoint[] | null;
  error: string | null;
}) {
  if (loading && endpoints === null) {
    return <RouteEndpointsSkeleton />;
  }
  if (error) {
    return (
      <p className="form__msg--err" role="alert" style={{ margin: 0 }}>
        Couldn&rsquo;t load endpoints: {error}
      </p>
    );
  }
  if (!endpoints || endpoints.length === 0) {
    return (
      <p className="field__hint" style={{ margin: 0 }}>
        No documented endpoint resolves to <code className="mono">{routeId}</code> today — its pattern may cover
        paths the OpenAPI spec doesn&rsquo;t list, or a lower-order route out-ranks it on every match.
      </p>
    );
  }
  return (
    <div className="epdrill">
      <p className="epdrill__count">
        {endpoints.length} {endpoints.length === 1 ? "endpoint" : "endpoints"} served by{" "}
        <code className="mono">{routeId}</code>
      </p>
      <div className="panel panel__scroll">
        <table className="table">
          <thead>
            <tr>
              <th>Method</th>
              <th>Path</th>
              <th>Summary</th>
            </tr>
          </thead>
          <tbody>
            {endpoints.map((e) => (
              <tr key={`${e.method} ${e.path}`}>
                <td>
                  <span className={`method method--${e.method.toLowerCase()}`}>{e.method}</span>
                </td>
                <td className="mono">{e.path}</td>
                <td className="epsummary">{e.summary || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function RouteEndpointsSkeleton() {
  return (
    <div className="epdrill" aria-live="polite" aria-busy="true">
      <span className="visually-hidden">Loading endpoints…</span>
      <Skeleton width={180} height={12} />
      <div className="panel panel__scroll" style={{ marginTop: 10 }} aria-hidden="true">
        <table className="table">
          <tbody>
            {Array.from({ length: 4 }).map((_, i) => (
              <tr key={i}>
                <td style={{ width: 72 }}>
                  <Skeleton width={52} height={18} radius={7} />
                </td>
                <td style={{ width: "38%" }}>
                  <Skeleton width={`${55 + (i % 3) * 12}%`} />
                </td>
                <td>
                  <Skeleton width={`${40 + (i % 4) * 14}%`} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function LifecycleBadge({ lifecycle }: { lifecycle: string }) {
  const key = lifecycle.toLowerCase();
  // Speak the lifecycle vocabulary, not "Paused" — a retired route is 410 Gone, not temporarily off.
  const label = key.charAt(0).toUpperCase() + key.slice(1);
  return <span className={`badge badge--${key}`}>{label}</span>;
}
