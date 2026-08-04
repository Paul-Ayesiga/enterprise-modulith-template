import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Sidebar } from "./components/Sidebar";
import { getSession } from "./lib/auth";
import { adminBaseUrl, fetchHealth, grafanaUrl } from "./lib/gateway";
import "./globals.css";

export const metadata: Metadata = {
  title: "SMSOne Gateway — Admin",
  description: "Inspect and manage the SMSOne gateway's routes, services, and API catalog, live."
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f5f7f7" },
    { media: "(prefers-color-scheme: dark)", color: "#090c0c" }
  ]
};

// The sidebar shows live edge health, so the shell is dynamic like the pages it wraps.
export const dynamic = "force-dynamic";

// Runs before paint so the first frame is already in the right theme (no flash).
const themeScript = `(function(){try{var s=localStorage.getItem('theme');var d=s?s==='dark':window.matchMedia('(prefers-color-scheme:dark)').matches;document.documentElement.setAttribute('data-theme',d?'dark':'light');}catch(e){}})();`;

export default async function RootLayout({ children }: { children: ReactNode }) {
  const session = getSession();
  const health = await fetchHealth();

  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
        <a className="skip-link" href="#main">
          Skip to content
        </a>
        <Sidebar
          health={health}
          grafanaUrl={grafanaUrl()}
          routesUrl={`${adminBaseUrl()}/actuator/gatewayroutes`}
          user={session ? session.name ?? session.email ?? session.sub : null}
        />
        <div className="shell__main">{children}</div>
      </body>
    </html>
  );
}
