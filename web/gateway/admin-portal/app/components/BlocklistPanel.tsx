"use client";

import { useFormState, useFormStatus } from "react-dom";
import { updateBlocklist, type ActionState } from "../lib/actions";
import type { AutoBlockRules, BlocklistEntry } from "../lib/gateway";
import { useActionToast } from "./Toast";

function ttl(seconds: number | undefined): string | null {
  if (seconds === undefined) return null;
  if (seconds >= 3600) return `expires in ${Math.round(seconds / 3600)}h`;
  if (seconds >= 60) return `expires in ${Math.round(seconds / 60)}m`;
  return `expires in ${seconds}s`;
}

/** Badge colour by tier: durable blocks read green, abuse blocks amber, the rest neutral. */
function sourceBadge(source: string): string {
  if (source === "auto") return "badge--deprecated";
  if (source === "persistent" || source === "config") return "badge--published";
  return "badge--open";
}

/**
 * The edge deny-list: what is blocked right now (manual, durable, and auto), block another source,
 * lift one. Every entry refuses matching clients before auth, quotas, or routing — the cheapest
 * possible "no". "Make permanent" writes a durable block that survives a gateway restart; auto
 * entries are the dynamic layer's work (a TTL); config entries come from YAML.
 */
export function BlocklistPanel({
  entries,
  allow,
  autoBlock,
  persistentEnabled,
  error
}: {
  entries: BlocklistEntry[];
  allow: string[];
  autoBlock: AutoBlockRules;
  persistentEnabled: boolean;
  error: string | null;
}) {
  const [state, formAction] = useFormState<ActionState, FormData>(updateBlocklist, null);
  useActionToast(state);

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
                  <span className={`badge ${sourceBadge(entry.source)}`}>{entry.source}</span>
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
            <div className="field">
              <label className="field__label" htmlFor="blocklist-cidr">
                Block a source
              </label>
              {/* Input + button share ONE centered row so the button lines up with the input box. The
                  hint lives below (outside this row) — inside it, the button would align to the bottom of
                  the hint and float low. Wraps the button under the input on narrow widths. */}
              <div style={{ display: "flex", flexWrap: "wrap", gap: 12, alignItems: "center" }}>
                <div style={{ flex: "1 1 260px" }}>
                  <input
                    id="blocklist-cidr"
                    name="cidr"
                    placeholder="203.0.113.0/24"
                    autoComplete="off"
                    spellCheck={false}
                    required
                  />
                </div>
                <input type="hidden" name="blocked" value="true" />
                <BlockButton />
              </div>
              <span className="field__hint">
                Bare IPs normalize to /32. A runtime block lasts until the gateway restarts; tick
                “make permanent” to keep it (or bake it into <code>gateway.security.blocklist.cidrs</code>).
              </span>
            </div>
            {persistentEnabled ? (
              <label
                style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer", marginTop: 2 }}
              >
                <input type="checkbox" name="persist" value="true" />
                <input type="hidden" name="persist" value="false" />
                Make permanent <span className="field__hint">survives a gateway restart (durable)</span>
              </label>
            ) : (
              <input type="hidden" name="persist" value="false" />
            )}
          </form>
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
