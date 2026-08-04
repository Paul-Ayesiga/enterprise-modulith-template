"use client";

import { useState } from "react";
import type { RouteRow } from "../lib/gateway";
import { GlobeIcon, LockIcon } from "./Icons";
import { EditRouteForm, RouteRowActions } from "./RouteRowActions";

/** One route in the table: the data row, plus — when editing — a full-width editor row beneath it. */
export function RouteTableRow({ route, services }: { route: RouteRow; services: string[] }) {
  const [editing, setEditing] = useState(false);

  return (
    <>
      <tr>
        <td>
          <span className="mono route-id">{route.id}</span>
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
      {editing && (
        <tr>
          <td colSpan={7} style={{ background: "var(--panel-alt, transparent)" }}>
            <EditRouteForm route={route} services={services} onDone={() => setEditing(false)} />
          </td>
        </tr>
      )}
    </>
  );
}

function LifecycleBadge({ lifecycle }: { lifecycle: string }) {
  const key = lifecycle.toLowerCase();
  // Speak the lifecycle vocabulary, not "Paused" — a retired route is 410 Gone, not temporarily off.
  const label = key.charAt(0).toUpperCase() + key.slice(1);
  return <span className={`badge badge--${key}`}>{label}</span>;
}
