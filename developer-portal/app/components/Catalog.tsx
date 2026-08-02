"use client";

import { useId, useMemo, useState } from "react";
import type { Product, RouteSummary } from "../lib/gateway";
import { CheckIcon, CopyIcon, GlobeIcon, LockIcon, SearchIcon } from "./Icons";

export function Catalog({ products, apiBaseUrl }: { products: Product[]; apiBaseUrl: string }) {
  const [query, setQuery] = useState("");
  const searchId = useId();

  const filtered = useMemo(() => filterProducts(products, query), [products, query]);
  const matchedRoutes = filtered.reduce((n, p) => n + p.routes.length, 0);

  return (
    <div className="catalog-wrap">
      <div className="toolbar">
        <div className="search">
          <SearchIcon className="search__icon" />
          <label className="visually-hidden" htmlFor={searchId}>
            Filter APIs and routes
          </label>
          <input
            id={searchId}
            type="search"
            className="search__input"
            placeholder="Filter by product, route, or path…"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
        </div>
        <p className="toolbar__count" role="status" aria-live="polite">
          {query
            ? `${matchedRoutes} matching route${matchedRoutes === 1 ? "" : "s"}`
            : `${products.length} product${products.length === 1 ? "" : "s"}`}
        </p>
      </div>

      {filtered.length === 0 ? (
        <div className="state state--inline">
          <p className="state__body">
            No products or routes match &ldquo;<strong>{query}</strong>&rdquo;.
          </p>
        </div>
      ) : (
        <ul className="catalog" role="list">
          {filtered.map((product) => (
            <li key={product.id}>
              <ProductSection product={product} apiBaseUrl={apiBaseUrl} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ProductSection({ product, apiBaseUrl }: { product: Product; apiBaseUrl: string }) {
  const headingId = `product-${product.id}`;
  return (
    <section className="product" aria-labelledby={headingId}>
      <div className="product__head">
        <h2 className="product__name" id={headingId}>
          {product.name}
        </h2>
        <span className="product__count">
          {product.routes.length} route{product.routes.length === 1 ? "" : "s"}
        </span>
      </div>
      {product.description && <p className="product__desc">{product.description}</p>}
      <ul className="routes" role="list">
        {product.routes.map((route) => (
          <li key={route.id}>
            <RouteRow route={route} apiBaseUrl={apiBaseUrl} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function RouteRow({ route, apiBaseUrl }: { route: RouteSummary; apiBaseUrl: string }) {
  return (
    <div className="route">
      <div className="route__main">
        <code className="route__path">{route.paths.join("  ·  ") || "—"}</code>
        <span className="route__id">{route.id}</span>
      </div>
      <div className="route__meta">
        <AccessBadge authenticated={route.authenticated} />
        <LifecycleBadge lifecycle={route.lifecycle} />
        <CopyCurlButton command={curlFor(route, apiBaseUrl)} label={route.paths[0] ?? route.id} />
      </div>
    </div>
  );
}

/** A route's pattern turned into a concrete example path (drop the trailing glob). */
function toExamplePath(pattern: string): string {
  return pattern.replace(/\/\*\*$/, "").replace(/\/\*$/, "") || "/";
}

/** A ready-to-run curl for a route: bearer header only when the route needs a token. */
function curlFor(route: RouteSummary, apiBaseUrl: string): string {
  const path = toExamplePath(route.paths[0] ?? "/");
  const parts = ["curl"];
  if (route.authenticated) {
    parts.push('-H "Authorization: Bearer $TOKEN"');
  }
  parts.push('-H "Accept: application/json"');
  parts.push(`${apiBaseUrl}${path}`);
  return parts.join(" ");
}

function CopyCurlButton({ command, label }: { command: string; label: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(command);
    } catch {
      return; // clipboard blocked (non-secure context) — nothing better to offer here
    }
    setCopied(true);
    window.setTimeout(() => setCopied(false), 1600);
  }

  return (
    <button
      type="button"
      className={`curl-btn${copied ? " curl-btn--copied" : ""}`}
      onClick={copy}
      title={command}
      aria-label={copied ? "curl command copied to clipboard" : `Copy curl command for ${label}`}
    >
      {copied ? <CheckIcon /> : <CopyIcon />}
      <span aria-hidden="true">{copied ? "Copied" : "curl"}</span>
    </button>
  );
}

function AccessBadge({ authenticated }: { authenticated: boolean }) {
  if (authenticated) {
    return (
      <span className="badge badge--auth" title="Requires a bearer token">
        <LockIcon />
        Token
      </span>
    );
  }
  return (
    <span className="badge badge--open" title="No token required">
      <GlobeIcon />
      Open
    </span>
  );
}

function LifecycleBadge({ lifecycle }: { lifecycle: string }) {
  const key = lifecycle.toLowerCase();
  const label = key.charAt(0).toUpperCase() + key.slice(1);
  return (
    <span className={`badge badge--lifecycle badge--${key}`} title={`Lifecycle: ${label}`}>
      {label}
    </span>
  );
}

function filterProducts(products: Product[], query: string): Product[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return products;
  }
  const result: Product[] = [];
  for (const product of products) {
    const productMatches =
      product.name.toLowerCase().includes(q) ||
      product.id.toLowerCase().includes(q) ||
      (product.description ?? "").toLowerCase().includes(q);
    const routes = productMatches
      ? product.routes
      : product.routes.filter(
          (route) =>
            route.id.toLowerCase().includes(q) ||
            route.paths.some((path) => path.toLowerCase().includes(q))
        );
    if (productMatches || routes.length > 0) {
      result.push({ ...product, routes });
    }
  }
  return result;
}
