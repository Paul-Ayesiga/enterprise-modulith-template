import type { Metadata, Viewport } from "next";
import type { ReactNode } from "react";
import { Sidebar } from "./components/Sidebar";
import { ToastProvider } from "./components/Toast";
import { getSession } from "./lib/auth";
import { openApiUrl, routeTableUrl } from "./lib/gateway";
import "./globals.css";

export const metadata: Metadata = {
  title: "SMSOne Developer Portal",
  description: "The APIs available through the SMSOne gateway — products, routes, lifecycle, and access."
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f7f9" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0d13" }
  ]
};

// Runs before paint so the first frame is already in the right theme (no flash).
const themeScript = `(function(){try{var s=localStorage.getItem('theme');var d=s?s==='dark':window.matchMedia('(prefers-color-scheme:dark)').matches;document.documentElement.setAttribute('data-theme',d?'dark':'light');}catch(e){}})();`;

export default function RootLayout({ children }: { children: ReactNode }) {
  const session = getSession();

  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <script dangerouslySetInnerHTML={{ __html: themeScript }} />
        <a className="skip-link" href="#main">
          Skip to content
        </a>
        <ToastProvider>
          <Sidebar
            openApiUrl={openApiUrl()}
            routeTableUrl={routeTableUrl()}
            user={session ? session.name ?? session.email ?? session.sub : null}
          />
          <div className="shell__main">{children}</div>
        </ToastProvider>
      </body>
    </html>
  );
}
