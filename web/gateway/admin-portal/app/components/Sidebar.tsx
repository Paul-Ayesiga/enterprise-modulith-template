"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { ExternalIcon, GatewayMark, MenuIcon, NavIcon } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

const NAV = [
  { href: "/", label: "Overview", icon: "overview" },
  { href: "/routes", label: "Routes", icon: "routes" },
  { href: "/endpoints", label: "Endpoints", icon: "endpoints" },
  { href: "/consumers", label: "Consumers", icon: "consumers" },
  { href: "/ip-controls", label: "IP controls", icon: "blocklist" },
  { href: "/services", label: "Services", icon: "services" }
];

type SidebarProps = {
  health: string;
  grafanaUrl: string;
  routesUrl: string;
  user: string | null;
};

/**
 * The control-plane shell: a fixed sidebar on desktop, an off-canvas drawer on mobile. Each item is
 * its own page (route-based, active by path); the active org's edge health rides at the bottom.
 */
export function Sidebar({ health, grafanaUrl, routesUrl, user }: SidebarProps) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const up = health.toUpperCase() === "UP";

  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  const isActive = (href: string) =>
    href === "/" ? pathname === "/" : pathname === href || pathname.startsWith(`${href}/`);

  return (
    <>
      <header className="topbar">
        <button
          type="button"
          className="icon-button"
          aria-label="Open navigation"
          aria-expanded={open}
          onClick={() => setOpen(true)}
        >
          <MenuIcon />
        </button>
        <Link className="topbar__brand" href="/">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          Gateway
        </Link>
        <span className="topbar__spacer" />
        <ThemeToggle />
      </header>

      <div className="backdrop" data-open={open} onClick={() => setOpen(false)} aria-hidden="true" />

      <aside className="sidebar" data-open={open} aria-label="Control plane">
        <Link className="sidebar__brand" href="/" aria-label="SMSOne Gateway admin, home">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          <span>
            <span className="brand__title">SMSOne Gateway</span>{" "}
            <span className="brand__sub">admin</span>
          </span>
        </Link>

        <nav className="sidebar__nav" aria-label="Sections">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={`sidebar__link${isActive(item.href) ? " is-active" : ""}`}
              aria-current={isActive(item.href) ? "page" : undefined}
            >
              <span className="sidebar__icon">
                <NavIcon name={item.icon} />
              </span>
              {item.label}
            </Link>
          ))}

          <div className="sidebar__section">External</div>
          <a className="sidebar__link" href={grafanaUrl} target="_blank" rel="noreferrer">
            <span className="sidebar__icon">
              <NavIcon name="overview" />
            </span>
            Grafana
            <ExternalIcon className="sidebar__ext" />
          </a>
          <a className="sidebar__link" href={routesUrl} target="_blank" rel="noreferrer">
            <span className="sidebar__icon">
              <NavIcon name="routes" />
            </span>
            Raw routes
            <ExternalIcon className="sidebar__ext" />
          </a>
        </nav>

        <div className="sidebar__footer">
          <div className="sidebar__health">
            <span
              className={`pill-health ${up ? "pill-health--up" : "pill-health--down"}`}
              title={`Gateway health: ${health}`}
            >
              <span className="pill-health__dot" aria-hidden="true" />
              {up ? "Healthy" : health}
            </span>
          </div>
          <div className="sidebar__tools">
            <ThemeToggle />
            {user ? (
              <a className="sidebar__signout" href="/api/auth/logout" title={`Signed in as ${user}`}>
                Sign out
              </a>
            ) : (
              <a className="sidebar__signout" href="/api/auth/login">
                Sign in
              </a>
            )}
          </div>
          {user && <div className="sidebar__user">{user}</div>}
        </div>
      </aside>
    </>
  );
}
