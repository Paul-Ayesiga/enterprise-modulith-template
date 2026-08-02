"use client";

import { useState, useTransition } from "react";
import { deleteRoute } from "../lib/actions";
import { TrashIcon } from "./Icons";

/** Two-click delete (Delete → Confirm?) so a route isn't removed from the live edge by a stray click. */
export function DeleteRouteButton({ id }: { id: string }) {
  const [pending, start] = useTransition();
  const [confirming, setConfirming] = useState(false);
  const [error, setError] = useState<string | null>(null);

  function onClick() {
    setError(null);
    if (!confirming) {
      setConfirming(true);
      return;
    }
    start(async () => {
      const result = await deleteRoute(id);
      if (result && !result.ok) {
        setError(result.message);
      }
      setConfirming(false);
    });
  }

  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
      <button
        type="button"
        className="btn btn--danger"
        onClick={onClick}
        onBlur={() => setConfirming(false)}
        disabled={pending}
        aria-label={confirming ? `Confirm delete route ${id}` : `Delete route ${id}`}
      >
        <TrashIcon /> {pending ? "Removing…" : confirming ? "Confirm?" : "Delete"}
      </button>
      {error && (
        <span className="form__msg--err" role="alert" style={{ fontSize: "0.78rem" }}>
          {error}
        </span>
      )}
    </span>
  );
}
