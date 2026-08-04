"use client";

import { useEffect, useState, useTransition } from "react";
import { useFormState, useFormStatus } from "react-dom";
import { createKeyAction, revokeKeyAction, rotateKeyAction, type MintState } from "../credentials/actions";
import type { ApiKey, Minted } from "../lib/credentials";
import { ConfirmDialog } from "./ConfirmDialog";
import { CheckIcon, CopyIcon } from "./Icons";
import { useActionToast, useToast } from "./Toast";

export function ApiKeys({ keys }: { keys: ApiKey[] }) {
  const [state, formAction] = useFormState<MintState, FormData>(createKeyAction, null);
  const [revealed, setRevealed] = useState<Minted | null>(null);
  useActionToast(state);

  // Surface a newly-created key's secret (once). Rotation sets `revealed` directly from its row.
  useEffect(() => {
    if (state?.ok && state.minted) setRevealed(state.minted);
  }, [state]);

  return (
    <div>
      {revealed && <SecretReveal minted={revealed} onDismiss={() => setRevealed(null)} />}

      <section className="section">
        <h2 className="section__title">New API key</h2>
        <div className="panel">
          <form className="form" action={formAction}>
            <div className="form__row">
              <div className="field">
                <label className="field__label" htmlFor="key-name">
                  Name
                </label>
                <input id="key-name" name="name" placeholder="Mobile app (prod)" autoComplete="off" required />
                <span className="field__hint">A label so you recognise it later.</span>
              </div>
              <div className="field">
                <label className="field__label" htmlFor="key-perms">
                  Permissions
                </label>
                <input id="key-perms" name="permissions" placeholder="org:read, member:read" autoComplete="off" />
                <span className="field__hint">Comma-separated; a subset of your own. Blank = none.</span>
              </div>
            </div>
            <div className="form__actions">
              <MintButton />
            </div>
          </form>
        </div>
      </section>

      <section className="section">
        <h2 className="section__title">
          Your keys <span className="section__hint">({keys.length})</span>
        </h2>
        {keys.length === 0 ? (
          <div className="state state--inline">
            <p className="state__body">No keys yet — create one above.</p>
          </div>
        ) : (
          <div className="panel panel__scroll">
            <table className="keys-table">
              <thead>
                <tr>
                  <th>Name</th>
                  <th>Prefix</th>
                  <th>Permissions</th>
                  <th>Last used</th>
                  <th>
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {keys.map((k) => (
                  <KeyRow key={k.id} apiKey={k} onRotated={setRevealed} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function MintButton() {
  const { pending } = useFormStatus();
  return (
    <button type="submit" className="btn btn--primary" disabled={pending}>
      {pending ? "Creating…" : "Create key"}
    </button>
  );
}

function SecretReveal({ minted, onDismiss }: { minted: Minted; onDismiss: () => void }) {
  const fullKey = `${minted.prefix}.${minted.secret}`;
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(fullKey);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1600);
    } catch {
      /* clipboard unavailable */
    }
  }

  return (
    <div className="secret-reveal" role="alert">
      <div className="secret-reveal__head">
        <strong>Copy your key now — the secret is shown only once.</strong>
        <button className="btn btn--sm btn--ghost" type="button" onClick={onDismiss}>
          Done
        </button>
      </div>
      <div className="secret-reveal__key">
        <code>{fullKey}</code>
        <button className="btn btn--sm btn--primary" type="button" onClick={copy}>
          {copied ? <CheckIcon /> : <CopyIcon />}
          {copied ? "Copied" : "Copy"}
        </button>
      </div>
      <p className="secret-reveal__hint">
        Send it as <code>X-Api-Key: {minted.prefix}.•••••</code>
      </p>
    </div>
  );
}

function KeyRow({ apiKey, onRotated }: { apiKey: ApiKey; onRotated: (m: Minted) => void }) {
  const toast = useToast();
  const [pending, start] = useTransition();
  const [confirmOpen, setConfirmOpen] = useState(false);

  function revoke() {
    start(async () => {
      const result = await revokeKeyAction(apiKey.id);
      toast(result.ok ? "success" : "error", result.message);
      setConfirmOpen(false);
    });
  }

  function rotate() {
    start(async () => {
      const result = await rotateKeyAction(apiKey.id);
      if (result.ok && result.minted) {
        onRotated(result.minted);
        toast("success", result.message);
      } else {
        toast("error", result.message);
      }
    });
  }

  return (
    <tr>
      <td className="route-id">{apiKey.name}</td>
      <td className="mono">{apiKey.prefix}…</td>
      <td>
        {apiKey.permissions.length > 0 ? (
          <span className="perms">
            {apiKey.permissions.map((p) => (
              <span className="perm" key={p}>
                {p}
              </span>
            ))}
          </span>
        ) : (
          <span className="field__hint">none</span>
        )}
      </td>
      <td className="field__hint">
        {apiKey.lastUsedAt ? new Date(apiKey.lastUsedAt).toLocaleDateString() : "never"}
      </td>
      <td style={{ textAlign: "right", whiteSpace: "nowrap" }}>
        <button className="btn btn--sm btn--ghost" type="button" onClick={rotate} disabled={pending}>
          Rotate
        </button>{" "}
        <button
          className="btn btn--sm btn--danger"
          type="button"
          onClick={() => setConfirmOpen(true)}
          disabled={pending}
        >
          Revoke
        </button>
        <ConfirmDialog
          open={confirmOpen}
          danger
          title={`Revoke "${apiKey.name}"?`}
          confirmLabel="Revoke key"
          pending={pending}
          onConfirm={revoke}
          onCancel={() => {
            if (!pending) setConfirmOpen(false);
          }}
          body={
            <>
              Any client still sending <code className="mono">{apiKey.prefix}…</code> will start getting{" "}
              <strong>401 Unauthorized</strong> immediately. This can&rsquo;t be undone — you&rsquo;d have to mint a
              new key. Rotate instead if you only want to replace the secret.
            </>
          }
        />
      </td>
    </tr>
  );
}
