"use client";

import { useEffect, useState } from "react";
import { MoonIcon, SunIcon } from "./Icons";

type Theme = "light" | "dark";

/**
 * Toggles the site theme. The initial `data-theme` is set by an inline script in the layout (before
 * paint, no flash); this reflects it, flips it on click, and persists the choice to localStorage.
 */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme | null>(null);

  useEffect(() => {
    const current = document.documentElement.getAttribute("data-theme");
    setTheme(current === "dark" ? "dark" : "light");
  }, []);

  function toggle() {
    const next: Theme = theme === "dark" ? "light" : "dark";
    setTheme(next);
    document.documentElement.setAttribute("data-theme", next);
    try {
      localStorage.setItem("theme", next);
    } catch {
      /* storage unavailable — the choice just won't persist */
    }
  }

  const isDark = theme === "dark";
  return (
    <button
      type="button"
      className="icon-button"
      onClick={toggle}
      aria-label={isDark ? "Switch to light theme" : "Switch to dark theme"}
      aria-pressed={isDark}
      title="Toggle theme"
    >
      <span className="icon-button__glyph" suppressHydrationWarning>
        {theme === null ? null : isDark ? <SunIcon /> : <MoonIcon />}
      </span>
    </button>
  );
}
