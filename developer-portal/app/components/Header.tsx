import Link from "next/link";
import { ExternalIcon, GatewayMark } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

type HeaderProps = {
  openApiUrl: string;
  routeTableUrl: string;
};

export function Header({ openApiUrl, routeTableUrl }: HeaderProps) {
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
          <Link className="site-nav__link" href="/">
            APIs
          </Link>
          <Link className="site-nav__link" href="/reference">
            Reference
          </Link>
          <Link className="site-nav__link" href="/try">
            Try it
          </Link>
          <Link className="site-nav__link" href="/changelog">
            Changelog
          </Link>
          <Link className="site-nav__link" href="/support">
            Support
          </Link>
          <Link className="site-nav__link" href="/credentials">
            Credentials
          </Link>
          <span className="site-nav__sep" aria-hidden="true" />
          <a className="site-nav__link" href={openApiUrl} target="_blank" rel="noreferrer">
            OpenAPI
            <ExternalIcon />
          </a>
          <a className="site-nav__link" href={routeTableUrl} target="_blank" rel="noreferrer">
            Route table
            <ExternalIcon />
          </a>
          <ThemeToggle />
        </nav>
      </div>
    </header>
  );
}
