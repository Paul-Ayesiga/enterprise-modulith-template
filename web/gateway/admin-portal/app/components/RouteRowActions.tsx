"use client";

import { useEffect, useState, useTransition } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { deleteRoute, setLifecycle, updateRoute, type ActionState } from "../lib/actions";
import type { RouteRow } from "../lib/gateway";
import { ConfirmDialog } from "./ConfirmDialog";
import { CopyIcon, PencilIcon, PauseIcon, PlayIcon, TrashIcon } from "./Icons";
import { RowMenu } from "./RowMenu";
import { useActionToast, useToast } from "./Toast";

/**
 * The route as a `gateway.routes` YAML snippet — paste it into application.yml to promote a runtime
 * or durable route into the git-reviewed baseline. Only set fields are emitted (the compact
 * constructor defaults the rest), matching the config the seed loader reads.
 */
function routeToYaml(route: RouteRow): string {
  const lines = [
    `- id: ${route.id}`,
    `  order: ${route.order}`,
    `  service-id: ${route.serviceId}`,
    `  predicates:`,
    `    - kind: PATH`,
    `      args: [${route.paths.join(", ")}]`
  ];
  if (route.authenticated) {
    lines.push(`  auth:`, `    authenticated: true`);
  }
  if (route.rateLimited) {
    lines.push(`  traffic:`, `    rate-limited: true`);
  }
  if (route.lifecycle && route.lifecycle.toUpperCase() !== "PUBLISHED") {
    lines.push(`  lifecycle:`, `    status: ${route.lifecycle.toLowerCase()}`);
    if (route.sunset) lines.push(`    sunset: "${route.sunset}"`);
  }
  return lines.join("\n");
}

/**
 * The per-route actions, collapsed into one "⋯" menu so the row height stays fixed. It follows the
 * lifecycle PROGRESSION so nothing jumps straight to 410: a Published route can only be Deprecated
 * (still serves, warns with Deprecation + Sunset); a Deprecated route can then be Retired (410) or
 * Republished; a Retired route can be Republished. The two irreversible-on-the-edge actions — Retire
 * and Delete — open a confirm dialog; every action's result is announced as a toast.
 */
export function RouteRowActions({
  route,
  editing,
  onToggleEdit
}: {
  route: RouteRow;
  services: string[];
  editing: boolean;
  onToggleEdit: () => void;
}) {
  const toast = useToast();
  const [pending, start] = useTransition();
  const [dialog, setDialog] = useState<null | "retire" | "delete">(null);
  const lifecycle = route.lifecycle.toUpperCase();

  function copyYaml() {
    navigator.clipboard
      .writeText(routeToYaml(route))
      .then(() => toast("success", `Copied the ${route.id} YAML — paste it into application.yml.`))
      .catch(() => toast("error", "Couldn't copy to the clipboard."));
  }

  function move(to: "PUBLISHED" | "DEPRECATED") {
    start(async () => {
      const result = await setLifecycle(route.id, to);
      if (result) toast(result.ok ? "success" : "error", result.message);
    });
  }

  function confirmRetire() {
    start(async () => {
      const result = await setLifecycle(route.id, "RETIRED");
      if (result) toast(result.ok ? "success" : "error", result.message);
      setDialog(null);
    });
  }

  function confirmDelete() {
    start(async () => {
      const result = await deleteRoute(route.id);
      if (result) toast(result.ok ? "success" : "error", result.message);
      setDialog(null);
    });
  }

  const closeDialog = () => {
    if (!pending) setDialog(null);
  };

  return (
    <>
      <RowMenu label={`Actions for route ${route.id}`}>
        {(close) => (
          <>
            {lifecycle === "PUBLISHED" && (
              <button
                type="button"
                role="menuitem"
                className="rowmenu__item"
                onClick={() => {
                  move("DEPRECATED");
                  close();
                }}
              >
                <PauseIcon /> Deprecate <span className="rowmenu__note">warns, still serves</span>
              </button>
            )}
            {lifecycle === "DEPRECATED" && (
              <>
                <button
                  type="button"
                  role="menuitem"
                  className="rowmenu__item"
                  onClick={() => {
                    move("PUBLISHED");
                    close();
                  }}
                >
                  <PlayIcon /> Republish
                </button>
                <button
                  type="button"
                  role="menuitem"
                  className="rowmenu__item rowmenu__item--danger"
                  onClick={() => {
                    setDialog("retire");
                    close();
                  }}
                >
                  <PauseIcon /> Retire <span className="rowmenu__note">410 Gone</span>
                </button>
              </>
            )}
            {lifecycle === "RETIRED" && (
              <button
                type="button"
                role="menuitem"
                className="rowmenu__item"
                onClick={() => {
                  move("PUBLISHED");
                  close();
                }}
              >
                <PlayIcon /> Republish
              </button>
            )}
            <div className="rowmenu__sep" />
            <button
              type="button"
              role="menuitem"
              className="rowmenu__item"
              onClick={() => {
                onToggleEdit();
                close();
              }}
            >
              <PencilIcon /> {editing ? "Close editor" : "Edit"}
            </button>
            <button
              type="button"
              role="menuitem"
              className="rowmenu__item"
              onClick={() => {
                copyYaml();
                close();
              }}
            >
              <CopyIcon /> Copy as YAML <span className="rowmenu__note">for application.yml</span>
            </button>
            <button
              type="button"
              role="menuitem"
              className="rowmenu__item rowmenu__item--danger"
              onClick={() => {
                setDialog("delete");
                close();
              }}
            >
              <TrashIcon /> Delete
            </button>
          </>
        )}
      </RowMenu>

      <ConfirmDialog
        open={dialog === "retire"}
        danger
        title={`Retire ${route.id}?`}
        confirmLabel="Retire — answer 410"
        pending={pending}
        onConfirm={confirmRetire}
        onCancel={closeDialog}
        body={
          <>
            The edge will answer <strong>410 Gone</strong> to every call on this route&rsquo;s paths (
            {route.paths.join(", ") || "—"}). This is the hard cutoff, past deprecation warnings — you can
            Republish it later. Runtime change: a gateway restart reverts to the YAML lifecycle.
          </>
        }
      />
      <ConfirmDialog
        open={dialog === "delete"}
        danger
        title={`Delete ${route.id}?`}
        confirmLabel="Delete route"
        pending={pending}
        onConfirm={confirmDelete}
        onCancel={closeDialog}
        body={
          <>
            Removes <code className="mono">{route.id}</code> from the live edge immediately — its paths fall
            back to whatever lower-priority route matches (usually the <code className="mono">platform-api</code>{" "}
            catch-all). A durable route is also dropped from the durable store.
          </>
        }
      />
    </>
  );
}

/** The expanded editor — rendered as a full-width row directly under the route it edits. */
export function EditRouteForm({ route, services, onDone }: { route: RouteRow; services: string[]; onDone: () => void }) {
  const [state, formAction] = useFormState<ActionState, FormData>(updateRoute, null);
  useActionToast(state);

  // A successful save has already revalidated the table; fold the editor away so the row settles.
  useEffect(() => {
    if (state?.ok) onDone();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [state]);

  return (
    <form className="form" action={formAction}>
      <input type="hidden" name="id" value={route.id} />
      <div className="form__row">
        <div className="field">
          <label className="field__label" htmlFor={`edit-path-${route.id}`}>
            Path pattern
          </label>
          <input
            id={`edit-path-${route.id}`}
            name="path"
            defaultValue={route.paths[0] ?? ""}
            placeholder="/api/v1/reports/**"
            autoComplete="off"
            spellCheck={false}
          />
          <span className="field__hint">Leave unchanged to keep the current predicate.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`edit-service-${route.id}`}>
            Service
          </label>
          <input
            id={`edit-service-${route.id}`}
            name="serviceId"
            defaultValue={route.serviceId}
            list={`services-${route.id}`}
            autoComplete="off"
            spellCheck={false}
          />
          <datalist id={`services-${route.id}`}>
            {services.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
          <span className="field__hint">Must be a registered service.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`edit-order-${route.id}`}>
            Order
          </label>
          <input
            id={`edit-order-${route.id}`}
            name="order"
            type="number"
            min={0}
            defaultValue={route.order}
            autoComplete="off"
          />
          <span className="field__hint">Lower wins when paths overlap.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`edit-lifecycle-${route.id}`}>
            Lifecycle
          </label>
          <select id={`edit-lifecycle-${route.id}`} name="lifecycle" defaultValue={route.lifecycle}>
            <option value="PUBLISHED">Published — serves normally</option>
            <option value="DEPRECATED">Deprecated — serves + warns (Deprecation/Sunset)</option>
            <option value="RETIRED">Retired — 410 Gone</option>
          </select>
          <span className="field__hint">Scopes, timeouts, caching, and transforms are preserved.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor={`edit-sunset-${route.id}`}>
            Sunset <span className="field__hint">(optional)</span>
          </label>
          <input
            id={`edit-sunset-${route.id}`}
            name="sunset"
            defaultValue={route.sunset ?? ""}
            placeholder="Wed, 31 Dec 2026 23:59:59 GMT"
            autoComplete="off"
            spellCheck={false}
          />
          <span className="field__hint">Sent as the Sunset header while deprecated (and on the 410).</span>
        </div>
      </div>
      <div className="form__row" style={{ marginTop: 4 }}>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="authenticated" value="true" defaultChecked={route.authenticated} />
          <input type="hidden" name="authenticated" value="false" />
          Require token <span className="field__hint">(401 without a valid bearer)</span>
        </label>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="rateLimited" value="true" defaultChecked={route.rateLimited} />
          <input type="hidden" name="rateLimited" value="false" />
          Rate limit <span className="field__hint">(shared token bucket, 429 over it)</span>
        </label>
      </div>
      <div className="form__actions">
        <SaveButton />
        <button type="button" className="btn btn--ghost" onClick={onDone}>
          Close
        </button>
      </div>
      <p className="field__hint">
        Runtime tier: edits apply instantly and last until the gateway restarts — YAML is the durable truth.
      </p>
    </form>
  );
}

function SaveButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" className="btn btn--primary" disabled={pending}>
      {pending ? "Saving…" : "Save changes"}
    </button>
  );
}
