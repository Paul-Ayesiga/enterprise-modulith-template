"use client";

import { useEffect, useState } from "react";
import { ExternalIcon, GatewayMark, MenuIcon, NavIcon } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

const SECTIONS = [
  { id: "overview", label: "Overview", icon: "overview" },
  { id: "routes", label: "Routes", icon: "routes" },
  { id: "consumers", label: "Consumers", icon: "consumers" },
  { id: "blocklist", label: "IP controls", icon: "blocklist" },
  { id: "services", label: "Services", icon: "services" }
];

type SidebarProps = {
  health: string;
  grafanaUrl: string;
  routesUrl: string;
  user: string | null;
};

/**
 * The control-plane shell: a fixed sidebar on desktop, an off-canvas drawer on mobile. Section links
 * scroll to the dashboard's sections and track the one in view (scrollspy). The active org's edge
 * health rides at the bottom so an operator always sees it.
 */
export function Sidebar({ health, grafanaUrl, routesUrl, user }: SidebarProps) {
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(SECTIONS[0].id);
  const up = health.toUpperCase() === "UP";

  useEffect(() => {
    const observer = new IntersectionObserver(
      (entries) => {
        const visible = entries
          .filter((e) => e.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (visible) setActive(visible.target.id);
      },
      { rootMargin: "-45% 0px -50% 0px", threshold: [0, 0.2, 0.6, 1] }
    );
    SECTIONS.forEach((s) => {
      const el = document.getElementById(s.id);
      if (el) observer.observe(el);
    });
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);

  function go(id: string) {
    setOpen(false);
    document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
    setActive(id);
  }

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
        <span className="topbar__brand">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          Gateway
        </span>
        <span className="topbar__spacer" />
        <ThemeToggle />
      </header>

      <div className="backdrop" data-open={open} onClick={() => setOpen(false)} aria-hidden="true" />

      <aside className="sidebar" data-open={open} aria-label="Control plane">
        <div className="sidebar__brand">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          <span>
            <span className="brand__title">SMSOne Gateway</span>{" "}
            <span className="brand__sub">admin</span>
          </span>
        </div>

        <nav className="sidebar__nav" aria-label="Sections">
          {SECTIONS.map((s) => (
            <a
              key={s.id}
              href={`#${s.id}`}
              className={`sidebar__link${active === s.id ? " is-active" : ""}`}
              aria-current={active === s.id ? "true" : undefined}
              onClick={(e) => {
                e.preventDefault();
                go(s.id);
              }}
            >
              <span className="sidebar__icon">
                <NavIcon name={s.icon} />
              </span>
              {s.label}
            </a>
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
