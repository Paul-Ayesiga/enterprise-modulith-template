"use client";

import { type ReactNode, useEffect, useRef, useState } from "react";
import { KebabIcon } from "./Icons";

/**
 * A compact per-row actions menu behind a single "⋯" trigger — keeps the row height fixed instead of
 * distorting it with a stack of buttons. The panel is FIXED-positioned (coordinates taken from the
 * trigger) so it escapes the table's horizontal-scroll container, which would otherwise clip it; it
 * closes on outside-click, Escape, scroll, or resize.
 */
export function RowMenu({
  label,
  onClose,
  children
}: {
  label: string;
  onClose?: () => void;
  children: (close: () => void) => ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<{ top: number; right: number }>({ top: 0, right: 0 });
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);

  function close() {
    setOpen(false);
    onClose?.();
  }

  function toggle() {
    if (open) {
      close();
      return;
    }
    const r = triggerRef.current?.getBoundingClientRect();
    if (r) setPos({ top: r.bottom + 4, right: Math.max(8, window.innerWidth - r.right) });
    setOpen(true);
  }

  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (!panelRef.current?.contains(e.target as Node) && !triggerRef.current?.contains(e.target as Node)) {
        close();
      }
    };
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && close();
    const dismiss = () => close();
    document.addEventListener("mousedown", onDown);
    document.addEventListener("keydown", onKey);
    window.addEventListener("scroll", dismiss, true);
    window.addEventListener("resize", dismiss);
    return () => {
      document.removeEventListener("mousedown", onDown);
      document.removeEventListener("keydown", onKey);
      window.removeEventListener("scroll", dismiss, true);
      window.removeEventListener("resize", dismiss);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className="icon-button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={label}
        onClick={toggle}
      >
        <KebabIcon />
      </button>
      {open && (
        <div ref={panelRef} className="rowmenu__panel" role="menu" style={{ top: pos.top, right: pos.right }}>
          {children(close)}
        </div>
      )}
    </>
  );
}
