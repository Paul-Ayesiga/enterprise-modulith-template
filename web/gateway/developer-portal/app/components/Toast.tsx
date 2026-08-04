"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { createPortal } from "react-dom";

export type ToastKind = "success" | "error" | "info";
type Toast = { id: number; kind: ToastKind; message: string };

type ToastFn = (kind: ToastKind, message: string) => void;
const ToastContext = createContext<ToastFn | null>(null);

const AUTO_DISMISS_MS = 5000;

/**
 * A minimal toast surface: transient, stacked, self-dismissing confirmations for actions whose result
 * would otherwise be an inline message that scrolls out of view. Mounted once at the app shell; every
 * client component reaches it through {@link useToast}. Errors are announced assertively (role=alert),
 * the rest politely.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(1);

  const dismiss = useCallback((id: number) => setToasts((list) => list.filter((t) => t.id !== id)), []);

  const push = useCallback<ToastFn>((kind, message) => {
    const id = nextId.current++;
    setToasts((list) => [...list, { id, kind, message }]);
  }, []);

  return (
    <ToastContext.Provider value={push}>
      {children}
      <ToastViewport toasts={toasts} onDismiss={dismiss} />
    </ToastContext.Provider>
  );
}

/** The toast dispatch — stable across renders, safe to list in effect deps. */
export function useToast(): ToastFn {
  const ctx = useContext(ToastContext);
  if (!ctx) {
    throw new Error("useToast must be used within <ToastProvider>.");
  }
  return ctx;
}

/**
 * Fires a toast whenever a server-action result (a new object each submission from useFormState)
 * changes — so forms don't have to wire the effect themselves. Returns nothing; the toast is the
 * side effect. Skips the initial null state so a freshly-mounted form is silent.
 */
export function useActionToast(state: { ok: boolean; message: string } | null) {
  const toast = useToast();
  const seen = useRef(state);
  useEffect(() => {
    if (state && state !== seen.current) {
      seen.current = state;
      toast(state.ok ? "success" : "error", state.message);
    }
  }, [state, toast]);
}

function ToastViewport({ toasts, onDismiss }: { toasts: Toast[]; onDismiss: (id: number) => void }) {
  // Defer the portal until after mount: on the server — and on the client's first, hydrating render —
  // this returns null, so the server HTML and the initial client tree match. The toaster is appended to
  // <body> only once hydration is done; portaling during hydration is what triggered the "<div> in
  // <body>" mismatch.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);
  if (!mounted) return null;
  return createPortal(
    <div className="toaster" role="region" aria-label="Notifications">
      {toasts.map((t) => (
        <ToastItem key={t.id} toast={t} onDismiss={onDismiss} />
      ))}
    </div>,
    document.body
  );
}

function ToastItem({ toast, onDismiss }: { toast: Toast; onDismiss: (id: number) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => onDismiss(toast.id), AUTO_DISMISS_MS);
    return () => window.clearTimeout(timer);
  }, [toast.id, onDismiss]);

  return (
    <div
      className={`toast toast--${toast.kind}`}
      role={toast.kind === "error" ? "alert" : "status"}
      aria-live={toast.kind === "error" ? "assertive" : "polite"}
    >
      <span className="toast__dot" aria-hidden="true" />
      <p className="toast__msg">{toast.message}</p>
      <button type="button" className="toast__close" aria-label="Dismiss" onClick={() => onDismiss(toast.id)}>
        ✕
      </button>
    </div>
  );
}
