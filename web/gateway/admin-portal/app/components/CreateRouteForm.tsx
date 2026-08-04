"use client";

import { useFormState, useFormStatus } from "react-dom";
import { createRoute, type ActionState } from "../lib/actions";
import { PlusIcon } from "./Icons";

type CreateRouteFormProps = {
  services: string[];
  /** Pre-fill from an "Endpoints → carve out a route" jump. */
  defaultId?: string;
  defaultPath?: string;
  defaultOrder?: string;
};

export function CreateRouteForm({ services, defaultId, defaultPath, defaultOrder }: CreateRouteFormProps) {
  const [state, formAction] = useFormState<ActionState, FormData>(createRoute, null);
  const carving = Boolean(defaultPath);

  return (
    <form className="form" action={formAction}>
      {carving && (
        <p className="form__note">
          Carving a dedicated route for <code>{defaultPath}</code>. Order <strong>{defaultOrder ?? "5"}</strong>{" "}
          places it ahead of the coarse product routes, so this exact path is controlled on its own —
          review and register.
        </p>
      )}
      <div className="form__row">
        <div className="field">
          <label className="field__label" htmlFor="route-id">
            Route id
          </label>
          <input
            id="route-id"
            name="id"
            defaultValue={defaultId ?? ""}
            placeholder="reports-api"
            autoComplete="off"
            spellCheck={false}
            required
          />
          <span className="field__hint">A slug — appears in the route table and logs.</span>
        </div>
        <div className="field">
          <label className="field__label" htmlFor="route-path">
            Path pattern
          </label>
          <input
            id="route-path"
            name="path"
            defaultValue={defaultPath ?? ""}
            placeholder="/api/v1/reports/**"
            autoComplete="off"
            spellCheck={false}
            required
          />
          <span className="field__hint">
            Ant-style — <code>**</code> matches any suffix; an exact path (no <code>**</code>) matches only
            itself.
          </span>
        </div>
      </div>
      <div className="form__row">
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
        <div className="field">
          <label className="field__label" htmlFor="route-order">
            Order <span className="field__hint">(optional)</span>
          </label>
          <input
            id="route-order"
            name="order"
            type="number"
            min={0}
            defaultValue={defaultOrder ?? ""}
            placeholder="1000"
            autoComplete="off"
          />
          <span className="field__hint">Lower wins when paths overlap. Blank = behind the seeded routes.</span>
        </div>
      </div>
      <div className="form__row" style={{ marginTop: 4 }}>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="authenticated" value="true" defaultChecked={carving} />
          <input type="hidden" name="authenticated" value="false" />
          Require token <span className="field__hint">(401 without a valid bearer)</span>
        </label>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="rateLimited" value="true" defaultChecked={carving} />
          <input type="hidden" name="rateLimited" value="false" />
          Rate limit <span className="field__hint">(shared token bucket, 429 over it)</span>
        </label>
        <label style={{ display: "inline-flex", alignItems: "center", gap: 8, cursor: "pointer" }}>
          <input type="checkbox" name="persist" value="true" defaultChecked={carving} />
          <input type="hidden" name="persist" value="false" />
          Keep after restart <span className="field__hint">(durable — survives a gateway restart)</span>
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
        <strong>Runtime vs durable:</strong> a route takes effect instantly. Without “keep after restart” it
        lives only in gateway memory and a restart re-seeds it away; with it, the route is stored durably
        and re-applied on every boot. Richer topology (scopes, tenant templates, timeouts, caching, product
        grouping) still belongs in <code>gateway/app/…/application.yml</code>.
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
