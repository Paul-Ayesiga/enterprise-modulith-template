"use client";

import { useState, useTransition } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { setLifecycle, updateRoute, type ActionState } from "../lib/actions";
import type { RouteRow } from "../lib/gateway";
import { DeleteRouteButton } from "./DeleteRouteButton";
import { PencilIcon, PauseIcon, PlayIcon } from "./Icons";

/**
 * The per-route action cluster, following the lifecycle PROGRESSION so nothing jumps straight to 410:
 * a Published route can only be Deprecated (still serves, warns with Deprecation + Sunset); a
 * Deprecated route can then be Retired (410 Gone) or Republished; a Retired route can be Republished.
 * Retirement is always preceded by a deprecate step. (The Edit row's dropdown still lets you set any
 * state deliberately.) Edit expands an in-place editor; Delete removes the route.
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
  const [pending, start] = useTransition();
  const [error, setError] = useState<string | null>(null);
  const lifecycle = route.lifecycle.toUpperCase();

  function move(to: "PUBLISHED" | "DEPRECATED" | "RETIRED") {
    setError(null);
    start(async () => {
      const result = await setLifecycle(route.id, to);
      if (result && !result.ok) {
        setError(result.message);
      }
    });
  }

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
      {lifecycle === "PUBLISHED" && (
        <button
          type="button"
          className="btn btn--ghost"
          onClick={() => move("DEPRECATED")}
          disabled={pending}
          title="Deprecate — still serves, but warns callers with Deprecation + Sunset headers"
        >
          <PauseIcon /> {pending ? "…" : "Deprecate"}
        </button>
      )}
      {lifecycle === "DEPRECATED" && (
        <>
          <button
            type="button"
            className="btn btn--danger"
            onClick={() => move("RETIRED")}
            disabled={pending}
            title="Retire — the edge answers 410 Gone. The deprecate warning has already run; this is the final step."
          >
            {pending ? "…" : "Retire"}
          </button>
          <button
            type="button"
            className="btn btn--ghost"
            onClick={() => move("PUBLISHED")}
            disabled={pending}
            title="Republish — serve normally again, warnings removed"
          >
            <PlayIcon /> Republish
          </button>
        </>
      )}
      {lifecycle === "RETIRED" && (
        <button
          type="button"
          className="btn btn--ghost"
          onClick={() => move("PUBLISHED")}
          disabled={pending}
          title="Republish — serve normally again"
        >
          <PlayIcon /> {pending ? "…" : "Republish"}
        </button>
      )}
      <button
        type="button"
        className="btn btn--ghost"
        onClick={onToggleEdit}
        aria-expanded={editing}
        aria-label={`Edit route ${route.id}`}
      >
        <PencilIcon /> {editing ? "Close" : "Edit"}
      </button>
      <DeleteRouteButton id={route.id} />
      {error && (
        <span className="form__msg--err" role="alert" style={{ fontSize: "0.78rem" }}>
          {error}
        </span>
      )}
    </span>
  );
}

/** The expanded editor — rendered as a full-width row directly under the route it edits. */
export function EditRouteForm({ route, services, onDone }: { route: RouteRow; services: string[]; onDone: () => void }) {
  const [state, formAction] = useFormState<ActionState, FormData>(updateRoute, null);

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
        {state && (
          <p
            className={`form__msg ${state.ok ? "form__msg--ok" : "form__msg--err"}`}
            role="status"
            aria-live="polite"
          >
            {state.message}
          </p>
        )}
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
