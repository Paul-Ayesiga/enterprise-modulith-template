import { adminBaseUrl } from "../lib/gateway";
import { PlugIcon } from "./Icons";

export function ErrorState({ message }: { message: string }) {
  return (
    <div className="state" role="alert">
      <span className="state__icon" aria-hidden="true">
        <PlugIcon />
      </span>
      <h2 className="state__title">Can&rsquo;t reach the gateway</h2>
      <p className="state__body">
        Tried <code>{adminBaseUrl()}</code> &mdash; {message}.
      </p>
      <p className="state__hint">
        Start it with <code>make gateway</code> (and <code>make run</code> for the modulith), or point{" "}
        <code>GATEWAY_ADMIN_URL</code> at the admin port.
      </p>
    </div>
  );
}

export function EmptyState() {
  return (
    <div className="state">
      <h2 className="state__title">No products published yet</h2>
      <p className="state__body">
        Give a route a <code>product</code> in the gateway config, then refresh this page.
      </p>
    </div>
  );
}
