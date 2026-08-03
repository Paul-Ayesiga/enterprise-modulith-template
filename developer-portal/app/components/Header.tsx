import Link from "next/link";
import { getSession } from "../lib/auth";
import { ExternalIcon, GatewayMark } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

type HeaderProps = {
  openApiUrl: string;
  routeTableUrl: string;
};

const NAV = [
  { href: "/", label: "APIs" },
  { href: "/getting-started", label: "Start" },
  { href: "/reference", label: "Reference" },
  { href: "/try", label: "Try it" },
  { href: "/usage", label: "Usage" },
  { href: "/webhooks", label: "Webhooks" },
  { href: "/changelog", label: "Changelog" },
  { href: "/support", label: "Support" },
  { href: "/credentials", label: "Credentials" }
];

export async function Header({ openApiUrl, routeTableUrl }: HeaderProps) {
  const session = getSession();

  return (
    <header className="site-header">
      <div className="site-header__inner">
        <Link className="brand" href="/" aria-label="SMSOne Developer Portal, home">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          <span className="brand__text">
            <span className="brand__name">SMSOne</span>
            <span className="brand__sub">Developer Portal</span>
          </span>
        </Link>

        <nav className="site-nav" aria-label="Portal">
          {NAV.map((item) => (
            <Link key={item.href} className="site-nav__link" href={item.href}>
              {item.label}
            </Link>
          ))}
          <span className="site-nav__sep" aria-hidden="true" />
          <a className="site-nav__link" href={openApiUrl} target="_blank" rel="noreferrer">
            OpenAPI
            <ExternalIcon />
          </a>
          <a className="site-nav__link" href={routeTableUrl} target="_blank" rel="noreferrer">
            Routes
            <ExternalIcon />
          </a>
          <ThemeToggle />
          {session && (
            <a
              className="site-nav__signout"
              href="/api/auth/logout"
              title={`Signed in as ${session.name ?? session.email ?? session.sub}`}
            >
              Sign out
            </a>
          )}
        </nav>
      </div>
    </header>
  );
}
