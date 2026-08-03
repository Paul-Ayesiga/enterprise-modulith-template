"use client";

import { useFormState, useFormStatus } from "react-dom";
import { createRoute, type ActionState } from "../lib/actions";
import { PlusIcon } from "./Icons";

export function CreateRouteForm({ services }: { services: string[] }) {
  const [state, formAction] = useFormState<ActionState, FormData>(createRoute, null);

  return (
    <form className="form" action={formAction}>
      <div className="form__row">
        <div className="field">
          <label className="field__label" htmlFor="route-id">
            Route id
          </label>
          <input id="route-id" name="id" placeholder="reports-api" autoComplete="off" spellCheck={false} required />
          <span className="field__hint">A slug — appears in the route table and logs.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor="route-path">
            Path pattern
          </label>
          <input
            id="route-path"
            name="path"
            placeholder="/api/v1/reports/**"
            autoComplete="off"
            spellCheck={false}
            required
          />
          <span className="field__hint">
            Ant-style — <code>**</code> matches any suffix.
          </span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor="route-service">
            Service
          </label>
          <input
            id="route-service"
            name="serviceId"
            placeholder="modulith"
            list="known-services"
            autoComplete="off"
            spellCheck={false}
            required
          />
          <datalist id="known-services">
            {services.map((s) => (
              <option key={s} value={s} />
            ))}
          </datalist>
          <span className="field__hint">Must be a registered service.</span>
        </div>
      </div>
      <div className="form__row" style={{ marginTop: 4 }}>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="authenticated" value="true" />
          <input type="hidden" name="authenticated" value="false" />
          Require token <span className="field__hint">(401 without a valid bearer)</span>
        </label>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="rateLimited" value="true" />
          <input type="hidden" name="rateLimited" value="false" />
          Rate limit <span className="field__hint">(shared token bucket, 429 over it)</span>
        </label>
      </div>
      <div className="form__actions">
        <SubmitButton />
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
        Scopes, tenant templates, timeouts, caching, and product grouping stay config-driven — add those
        in <code>gateway/app/…/application.yml</code>.
      </p>
    </form>
  );
}

function SubmitButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" className="btn btn--primary" disabled={pending}>
      <PlusIcon /> {pending ? "Registering…" : "Register route"}
    </button>
  );
}
