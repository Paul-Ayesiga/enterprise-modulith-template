import { getSession } from "../lib/auth";
import { ChartIcon, ExternalIcon, GatewayMark } from "./Icons";
import { ThemeToggle } from "./ThemeToggle";

export async function Header({
  health,
  grafanaUrl,
  routesUrl
}: {
  health: string;
  grafanaUrl: string;
  routesUrl: string;
}) {
  const up = health.toUpperCase() === "UP";
  const session = getSession();

  return (
    <header className="site-header">
      <div className="site-header__inner">
        <div className="brand">
          <span className="brand__mark">
            <GatewayMark />
          </span>
          <span>
            <span className="brand__title">SMSOne Gateway</span>{" "}
            <span className="brand__sub">admin</span>
          </span>
        </div>

        <span
          className={`pill-health ${up ? "pill-health--up" : "pill-health--down"}`}
          title={`Gateway health: ${health}`}
        >
          <span className="pill-health__dot" aria-hidden="true" />
          {up ? "Healthy" : health}
        </span>

        <span className="site-header__spacer" />

        <nav className="site-nav" aria-label="Tools">
          <a className="site-nav__link" href={grafanaUrl} target="_blank" rel="noreferrer">
            <ChartIcon /> Grafana <ExternalIcon />
          </a>
          <a className="site-nav__link" href={routesUrl} target="_blank" rel="noreferrer">
            Raw routes <ExternalIcon />
          </a>
        </nav>
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
      </div>
    </header>
  );
}
