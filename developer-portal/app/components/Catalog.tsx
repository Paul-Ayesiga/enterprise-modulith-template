"use client";

import { useId, useMemo, useState } from "react";
import type { Product, RouteSummary } from "../lib/gateway";
import { GlobeIcon, LockIcon, SearchIcon } from "./Icons";

export function Catalog({ products }: { products: Product[] }) {
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
              <ProductSection product={product} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function ProductSection({ product }: { product: Product }) {
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
            <RouteRow route={route} />
          </li>
        ))}
      </ul>
    </section>
  );
}

function RouteRow({ route }: { route: RouteSummary }) {
  return (
    <div className="route">
      <div className="route__main">
        <code className="route__path">{route.paths.join("  ·  ") || "—"}</code>
        <span className="route__id">{route.id}</span>
      </div>
      <div className="route__meta">
        <AccessBadge authenticated={route.authenticated} />
        <LifecycleBadge lifecycle={route.lifecycle} />
      </div>
    </div>
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
