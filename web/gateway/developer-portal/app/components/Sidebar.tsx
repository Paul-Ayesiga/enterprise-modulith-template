"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { ExternalIcon, GatewayMark, MenuIcon, NavIcon } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

const NAV = [
  { href: "/", label: "APIs", icon: "apis" },
  { href: "/getting-started", label: "Getting started", icon: "start" },
  { href: "/reference", label: "Reference", icon: "reference" },
  { href: "/try", label: "Try it", icon: "try" },
  { href: "/usage", label: "Usage", icon: "usage" },
  { href: "/webhooks", label: "Webhooks", icon: "webhooks" },
  { href: "/changelog", label: "Changelog", icon: "changelog" },
  { href: "/support", label: "Support", icon: "support" },
  { href: "/credentials", label: "Credentials", icon: "credentials" }
];

type SidebarProps = {
  openApiUrl: string;
  routeTableUrl: string;
  user: string | null;
};

/**
 * The portal shell: a fixed sidebar on desktop, an off-canvas drawer on mobile. Nav links highlight
 * the page in view (via the current path) and the drawer closes on navigation.
 */
export function Sidebar({ openApiUrl, routeTableUrl, user }: SidebarProps) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);

  // Close the mobile drawer whenever the route changes.
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  // Escape closes the drawer.
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
          <span className="icon-button__glyph">
            <MenuIcon />
          </span>
        </button>
        <Link className="topbar__brand" href="/">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          SMSOne
        </Link>
        <span className="topbar__spacer" />
        <ThemeToggle />
      </header>

      <div className="backdrop" data-open={open} onClick={() => setOpen(false)} aria-hidden="true" />

      <aside className="sidebar" data-open={open} aria-label="Developer portal">
        <Link className="sidebar__brand" href="/" aria-label="SMSOne Developer Portal, home">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          <span className="brand__text">
            <span className="brand__name">SMSOne</span>
            <span className="brand__sub">Developer Portal</span>
          </span>
        </Link>

        <nav className="sidebar__nav" aria-label="Portal">
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
          <a className="sidebar__link" href={openApiUrl} target="_blank" rel="noreferrer">
            <span className="sidebar__icon">
              <NavIcon name="doc" />
            </span>
            OpenAPI
            <ExternalIcon className="sidebar__ext" />
          </a>
          <a className="sidebar__link" href={routeTableUrl} target="_blank" rel="noreferrer">
            <span className="sidebar__icon">
              <NavIcon name="route" />
            </span>
            Route table
            <ExternalIcon className="sidebar__ext" />
          </a>
        </nav>

        <div className="sidebar__footer">
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
