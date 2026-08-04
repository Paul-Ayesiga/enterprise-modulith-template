"use client";

import { useFormState, useFormStatus } from "react-dom";
import { updateBlocklist, type ActionState } from "../lib/actions";
import type { AutoBlockRules, BlocklistEntry } from "../lib/gateway";

function ttl(seconds: number | undefined): string | null {
  if (seconds === undefined) return null;
  if (seconds >= 3600) return `expires in ${Math.round(seconds / 3600)}h`;
  if (seconds >= 60) return `expires in ${Math.round(seconds / 60)}m`;
  return `expires in ${seconds}s`;
}

/**
 * The edge deny-list: what is blocked right now (manual and auto), block another source, lift one.
 * Every entry refuses matching clients before auth, quotas, or routing — the cheapest possible "no".
 * Auto entries are the dynamic layer's work: sources that tripped the abuse rules, with a TTL.
 */
export function BlocklistPanel({
  entries,
  allow,
  autoBlock,
  error
}: {
  entries: BlocklistEntry[];
  allow: string[];
  autoBlock: AutoBlockRules;
  error: string | null;
}) {
  const [state, formAction] = useFormState<ActionState, FormData>(updateBlocklist, null);

  return (
    <div className="panel" style={{ padding: "0.9rem 1.1rem" }}>
      {error ? (
        <p className="field__hint" style={{ margin: 0 }}>
          Blocklist unavailable: {error}
        </p>
      ) : (
        <>
          <p className="field__hint" style={{ marginTop: 0 }}>
            {autoBlock.enabled
              ? `Auto-blocking on — ${autoBlock.threshold} denied responses (${autoBlock.statuses.join(
                  "/"
                )}) within ${autoBlock.window} → blocked for ${autoBlock.blockDuration}.`
              : "Auto-blocking off — only manual entries below apply."}
            {allow.length > 0 ? ` Allow-listed (never blocked): ${allow.join(", ")}.` : ""}
          </p>
          {entries.length === 0 ? (
            <p className="field__hint" style={{ marginTop: 0 }}>
              Nothing is blocked. Add an IP (203.0.113.9) or CIDR (203.0.113.0/24) below — it bites
              the very next request.
            </p>
          ) : (
            <ul className="blocklist" style={{ listStyle: "none", margin: "0 0 0.75rem", padding: 0 }}>
              {entries.map((entry) => (
                <li
                  key={`${entry.source}:${entry.cidr}`}
                  style={{ display: "flex", alignItems: "center", gap: "0.6rem", padding: "0.25rem 0" }}
                >
                  <code>{entry.cidr}</code>
                  <span className={`badge ${entry.source === "auto" ? "badge--deprecated" : "badge--open"}`}>
                    {entry.source}
                  </span>
                  {ttl(entry.expiresInSeconds) ? (
                    <span className="field__hint" style={{ margin: 0 }}>
                      {ttl(entry.expiresInSeconds)}
                    </span>
                  ) : null}
                  <form action={formAction} style={{ marginLeft: "auto" }}>
                    <input type="hidden" name="cidr" value={entry.cidr} />
                    <input type="hidden" name="blocked" value="false" />
                    <UnblockButton />
                  </form>
                </li>
              ))}
            </ul>
          )}
          <form className="form" action={formAction}>
            <div className="form__row" style={{ alignItems: "flex-end" }}>
              <div className="field" style={{ flex: 1 }}>
                <label className="field__label" htmlFor="blocklist-cidr">
                  Block a source
                </label>
                <input
                  id="blocklist-cidr"
                  name="cidr"
                  placeholder="203.0.113.0/24"
                  autoComplete="off"
                  spellCheck={false}
                  required
                />
                <span className="field__hint">
                  Bare IPs normalize to /32. Runtime entries last until restart — durable blocks go in
                  the gateway YAML (<code>gateway.security.blocklist.cidrs</code>).
                </span>
              </div>
              <input type="hidden" name="blocked" value="true" />
              <BlockButton />
            </div>
          </form>
          {state ? (
            <p
              className={`form__msg ${state.ok ? "form__msg--ok" : "form__msg--err"}`}
              role="status"
              aria-live="polite"
              style={{ marginBottom: 0 }}
            >
              {state.message}
            </p>
          ) : null}
        </>
      )}
    </div>
  );
}

function BlockButton() {
  const { pending } = useFormStatus();
  return (
    <button className="btn btn--primary" type="submit" disabled={pending}>
      {pending ? "Blocking…" : "Block"}
    </button>
  );
}

function UnblockButton() {
  const { pending } = useFormStatus();
  return (
    <button className="btn btn--ghost" type="submit" disabled={pending}>
      {pending ? "…" : "Unblock"}
    </button>
  );
}
