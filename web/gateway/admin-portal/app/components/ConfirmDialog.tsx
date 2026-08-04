"use client";

import { useEffect, useId, useRef, type ReactNode } from "react";
import { createPortal } from "react-dom";

/**
 * An accessible confirm modal for irreversible-on-the-live-edge actions (delete a route, retire it to
 * 410). Rendered through a portal so it escapes table scroll containers; it is an alertdialog with a
 * labelled title/body, focuses the confirm button on open, closes on Escape or backdrop click, and
 * blocks both while the action is pending so a double-confirm can't fire the mutation twice.
 */
export function ConfirmDialog({
  open,
  title,
  body,
  confirmLabel = "Confirm",
  cancelLabel = "Cancel",
  danger = false,
  pending = false,
  onConfirm,
  onCancel
}: {
  open: boolean;
  title: string;
  body: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
  pending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const confirmRef = useRef<HTMLButtonElement>(null);
  const titleId = useId();
  const bodyId = useId();

  useEffect(() => {
    if (!open) return;
    confirmRef.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !pending) onCancel();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open, pending, onCancel]);

  if (!open || typeof document === "undefined") return null;

  return createPortal(
    <div
      className="modal"
      onMouseDown={(e) => {
        if (e.target === e.currentTarget && !pending) onCancel();
      }}
    >
      <div className="modal__panel" role="alertdialog" aria-modal="true" aria-labelledby={titleId} aria-describedby={bodyId}>
        <h2 className="modal__title" id={titleId}>
          {title}
        </h2>
        <div className="modal__body" id={bodyId}>
          {body}
        </div>
        <div className="modal__actions">
          <button type="button" className="btn btn--ghost" onClick={onCancel} disabled={pending}>
            {cancelLabel}
          </button>
          <button
            ref={confirmRef}
            type="button"
            className={`btn ${danger ? "btn--danger-solid" : "btn--primary"}`}
            onClick={onConfirm}
            disabled={pending}
          >
            {pending ? "Working…" : confirmLabel}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}
