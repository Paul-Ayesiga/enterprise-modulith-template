"use client";

import { useId, useMemo, useState } from "react";
import type { OpenApiDoc, OpenApiOperation } from "../lib/gateway";
import { SearchIcon } from "./Icons";

export function Reference({ doc }: { doc: OpenApiDoc }) {
  const [query, setQuery] = useState("");
  const searchId = useId();
  const groups = useMemo(() => group(doc.operations, query), [doc.operations, query]);
  const count = groups.reduce((n, g) => n + g.ops.length, 0);

  return (
    <div>
      <div className="toolbar">
        <div className="search">
          <SearchIcon className="search__icon" />
          <label className="visually-hidden" htmlFor={searchId}>
            Filter operations
          </label>
          <input
            id={searchId}
            type="search"
            className="search__input"
            placeholder="Filter by path, summary, method, or area…"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
        </div>
        <p className="toolbar__count" role="status" aria-live="polite">
          {count} operation{count === 1 ? "" : "s"}
        </p>
      </div>

      {groups.length === 0 ? (
        <div className="state state--inline">
          <p className="state__body">
            No operations match &ldquo;<strong>{query}</strong>&rdquo;.
          </p>
        </div>
      ) : (
        groups.map((g) => (
          <section className="ref-group" key={g.tag} aria-label={g.tag}>
            <h2 className="ref-group__tag">{g.tag}</h2>
            <ul className="ref-ops" role="list">
              {g.ops.map((op) => (
                <li key={`${op.method} ${op.path}`}>
                  <OperationRow op={op} />
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </div>
  );
}

function OperationRow({ op }: { op: OpenApiOperation }) {
  return (
    <details className="op">
      <summary className="op__summary">
        <span className={`method method--${op.method.toLowerCase()}`}>{op.method}</span>
        <code className="op__path">{op.path}</code>
        {op.summary && <span className="op__blurb">{op.summary}</span>}
        {op.deprecated && <span className="badge badge--deprecated">Deprecated</span>}
      </summary>
      <div className="op__detail">
        {op.description && <p className="op__prose">{op.description}</p>}

        {op.parameters.length > 0 && (
          <>
            <h3 className="op__h">Parameters</h3>
            <ul className="op__params" role="list">
              {op.parameters.map((p) => (
                <li className="op__param" key={`${p.in}:${p.name}`}>
                  <code className="op__param-name">{p.name}</code>
                  <span className="op__param-in">{p.in}</span>
                  {p.type && <span className="op__param-type">{p.type}</span>}
                  {p.required && <span className="op__param-req">required</span>}
                  {p.description && <span className="op__param-desc">{p.description}</span>}
                </li>
              ))}
            </ul>
          </>
        )}

        {op.requestContentTypes.length > 0 && (
          <p className="op__meta">
            Request body: <code>{op.requestContentTypes.join(", ")}</code>
          </p>
        )}

        <h3 className="op__h">Responses</h3>
        <ul className="op__responses" role="list">
          {op.responses.map((r) => (
            <li key={r.status}>
              <span className={`status-pill status-pill--${statusClass(r.status)}`}>{r.status}</span>
              {r.description && <span className="op__resp-desc">{r.description}</span>}
            </li>
          ))}
        </ul>
      </div>
    </details>
  );
}

function statusClass(status: string): string {
  const n = Number.parseInt(status, 10);
  if (n >= 500) return "err";
  if (n >= 400) return "warn";
  if (n >= 200 && n < 300) return "ok";
  return "warn";
}

function group(ops: OpenApiOperation[], query: string): { tag: string; ops: OpenApiOperation[] }[] {
  const q = query.trim().toLowerCase();
  const filtered = q
    ? ops.filter(
        (o) =>
          o.path.toLowerCase().includes(q) ||
          o.tag.toLowerCase().includes(q) ||
          o.method.toLowerCase().includes(q) ||
          (o.summary ?? "").toLowerCase().includes(q)
      )
    : ops;
  const byTag = new Map<string, OpenApiOperation[]>();
  for (const op of filtered) {
    const list = byTag.get(op.tag) ?? [];
    list.push(op);
    byTag.set(op.tag, list);
  }
  return [...byTag.entries()].map(([tag, tagOps]) => ({ tag, ops: tagOps }));
}
