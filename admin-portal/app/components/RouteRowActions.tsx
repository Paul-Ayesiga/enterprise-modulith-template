"use client";

import { useState, useTransition } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { setLifecycle, updateRoute, type ActionState } from "../lib/actions";
import type { RouteRow } from "../lib/gateway";
import { DeleteRouteButton } from "./DeleteRouteButton";
import { PencilIcon, PauseIcon, PlayIcon } from "./Icons";

/**
 * The per-route action cluster: Pause/Resume (a one-click lifecycle flip — RETIRED answers 410 on the
 * edge, PUBLISHED serves again), Edit (expands an in-place editor row beneath), and Delete. Editing
 * goes through the gateway's update operation, which preserves the policies a delete-and-recreate
 * would lose (auth, traffic, transform).
 */
export function RouteRowActions({
  route,
  services,
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
  const paused = route.lifecycle === "RETIRED";

  function flipLifecycle() {
    setError(null);
    start(async () => {
      const result = await setLifecycle(route.id, paused ? "PUBLISHED" : "RETIRED");
      if (result && !result.ok) {
        setError(result.message);
      }
    });
  }

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      <button
        type="button"
        className="btn btn--ghost"
        onClick={flipLifecycle}
        disabled={pending}
        aria-label={paused ? `Resume route ${route.id}` : `Pause route ${route.id}`}
        title={paused ? "Resume — PUBLISHED serves again" : "Pause — RETIRED answers 410 Gone"}
      >
        {paused ? <PlayIcon /> : <PauseIcon />} {pending ? "…" : paused ? "Resume" : "Pause"}
      </button>
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
            <option value="PUBLISHED">PUBLISHED — serves normally</option>
            <option value="DEPRECATED">DEPRECATED — serves + warns (Sunset)</option>
            <option value="RETIRED">RETIRED — paused, 410 Gone</option>
          </select>
          <span className="field__hint">Scopes, timeouts, caching, and transforms are preserved.</span>
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
