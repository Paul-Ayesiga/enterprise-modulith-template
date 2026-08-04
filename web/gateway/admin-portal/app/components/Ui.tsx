// Small server-component building blocks shared by the admin pages: the page heading, KPI stats,
// and the gateway-unreachable state. No client behavior — they render on the server with the page.
import type { ReactNode } from "react";

export function PageIntro({ title, lede, children }: { title: string; lede?: string; children?: ReactNode }) {
  return (
    <section className="intro">
      <h1 className="intro__title">{title}</h1>
      {lede && <p className="intro__lede">{lede}</p>}
      {children}
    </section>
  );
}

export function Stat({ value, label, accent }: { value: number | string; label: string; accent?: boolean }) {
  return (
    <div className={`stat${accent ? " stat--accent" : ""}`}>
      <div className="stat__value">{value}</div>
      <div className="stat__label">{label}</div>
    </div>
  );
}

export function GatewayUnreachable({ baseUrl, error }: { baseUrl: string; error: string }) {
  return (
    <div className="state state--error" role="alert">
      <p className="state__title">Can&rsquo;t reach the gateway admin API</p>
      <p className="state__body">
        Tried <code>{baseUrl}</code>. Start the edge with <code>make gateway</code>, or point{" "}
        <code>GATEWAY_ADMIN_URL</code> at it. ({error})
      </p>
    </div>
  );
}
