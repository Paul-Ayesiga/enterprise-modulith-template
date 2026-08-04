"use client";

import Link from "next/link";
import { useState, useTransition } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { createWebhookAction, deleteWebhookAction, type CreateState } from "../webhooks/actions";
import type { EventType, Webhook } from "../lib/webhooks";
import { ConfirmDialog } from "./ConfirmDialog";
import { useActionToast, useToast } from "./Toast";

/** Create form + subscription list. The signing secret appears exactly once, in the create result. */
export function Webhooks({
  webhooks,
  eventTypes,
  activeId
}: {
  webhooks: Webhook[];
  eventTypes: EventType[];
  activeId: string | null;
}) {
  const [state, formAction] = useFormState<CreateState, FormData>(createWebhookAction, null);
  useActionToast(state);

  return (
    <>
      <form className="form" action={formAction}>
        <div className="field">
          <label className="field__label" htmlFor="wh-url">
            Endpoint URL
          </label>
          <input
            id="wh-url"
            name="url"
            placeholder="https://hooks.example.test/smsone"
            autoComplete="off"
            spellCheck={false}
            required
          />
          <span className="field__hint">
            We POST each event here, signed HMAC-SHA256 in <code>X-Webhook-Signature</code>.
          </span>
        </div>
        <div className="field">
          <span className="field__label">Events</span>
          <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 6 }}>
            {eventTypes.map((type) => (
              <label key={type.code} style={{ display: "inline-flex", alignItems: "baseline", gap: 8, cursor: "pointer" }}>
                <input type="checkbox" name="events" value={type.code} />
                <span>
                  <code>{type.code}</code>
                  <span className="field__hint" style={{ display: "block" }}>{type.description}</span>
                </span>
              </label>
            ))}
          </div>
        </div>
        <div className="form__actions">
          <CreateButton />
        </div>
      </form>

      {state?.created && (
        <div className="state" role="alert" style={{ marginTop: "1rem" }}>
          <p className="state__body">
            <strong>Signing secret — shown only this once:</strong>
            <br />
            <code className="mono" style={{ wordBreak: "break-all" }}>{state.created.secret}</code>
            <br />
            <span className="field__hint">
              Verify each delivery: HMAC-SHA256 over the exact raw body with this secret must equal the{" "}
              <code>X-Webhook-Signature</code> header (after <code>sha256=</code>). Reads from now on show it masked.
            </span>
          </p>
        </div>
      )}

      <div className="panel panel__scroll" style={{ marginTop: "1rem" }}>
        <table className="keys-table">
          <thead>
            <tr>
              <th>Endpoint</th>
              <th>Events</th>
              <th>Status</th>
              <th>
                <span className="mono">secret</span>
              </th>
              <th />
            </tr>
          </thead>
          <tbody>
            {webhooks.length === 0 ? (
              <tr>
                <td colSpan={5}>
                  <span className="field__hint">No webhooks yet — subscribe an endpoint above.</span>
                </td>
              </tr>
            ) : (
              webhooks.map((webhook) => (
                <WebhookRow key={webhook.id} webhook={webhook} active={webhook.id === activeId} />
              ))
            )}
          </tbody>
        </table>
      </div>
    </>
  );
}

function WebhookRow({ webhook, active }: { webhook: Webhook; active: boolean }) {
  const toast = useToast();
  const [pending, start] = useTransition();
  const [confirmOpen, setConfirmOpen] = useState(false);

  function onDelete() {
    start(async () => {
      const result = await deleteWebhookAction(webhook.id);
      toast(result.ok ? "success" : "error", result.message);
      setConfirmOpen(false);
    });
  }

  return (
    <tr>
      <td className="mono" style={{ wordBreak: "break-all" }}>{webhook.url}</td>
      <td>
        {webhook.events.map((event) => (
          <code key={event} style={{ display: "inline-block", marginRight: 6 }}>{event}</code>
        ))}
      </td>
      <td>
        <span className={`badge badge--${webhook.status === "ACTIVE" ? "published" : "retired"}`}>
          {webhook.status}
        </span>
      </td>
      <td className="mono">{webhook.secret}</td>
      <td style={{ whiteSpace: "nowrap", textAlign: "right" }}>
        <Link className="btn btn--sm btn--ghost" href={active ? "/webhooks" : `/webhooks?sub=${webhook.id}`}>
          {active ? "Hide deliveries" : "Deliveries"}
        </Link>{" "}
        <button
          type="button"
          className="btn btn--sm btn--danger"
          onClick={() => setConfirmOpen(true)}
          disabled={pending}
        >
          {pending ? "Removing…" : "Delete"}
        </button>
        <ConfirmDialog
          open={confirmOpen}
          danger
          title="Delete this webhook?"
          confirmLabel="Delete webhook"
          pending={pending}
          onConfirm={onDelete}
          onCancel={() => {
            if (!pending) setConfirmOpen(false);
          }}
          body={
            <>
              We&rsquo;ll stop delivering events to <code className="mono">{webhook.url}</code>. Any events fired
              after this are not queued or replayed — re-subscribe to start receiving them again.
            </>
          }
        />
      </td>
    </tr>
  );
}

function CreateButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" className="btn btn--primary" disabled={pending}>
      {pending ? "Creating…" : "Create webhook"}
    </button>
  );
}
