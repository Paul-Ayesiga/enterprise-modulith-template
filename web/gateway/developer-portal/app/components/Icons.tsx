// Small inline icons. `aria-hidden` by default — they decorate labelled controls, never stand alone.

type IconProps = { className?: string };

const base = (className?: string) => ({
  width: 16,
  height: 16,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.75,
  strokeLinecap: "round" as const,
  strokeLinejoin: "round" as const,
  "aria-hidden": true,
  className
});

export function GatewayMark({ className }: IconProps) {
  // A stylised edge: a node passing through a gate. Decorative brand mark.
  return (
    <svg {...base(className)} width={22} height={22}>
      <path d="M4 7v10M20 7v10" />
      <path d="M4 12h6m4 0h6" />
      <circle cx="12" cy="12" r="2.4" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function SearchIcon({ className }: IconProps) {
  return (
    <svg {...base(className)}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.2-3.2" />
    </svg>
  );
}

export function LockIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <rect x="5" y="11" width="14" height="9" rx="2" />
      <path d="M8 11V8a4 4 0 0 1 8 0v3" />
    </svg>
  );
}

export function GlobeIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <circle cx="12" cy="12" r="9" />
      <path d="M3 12h18M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18" />
    </svg>
  );
}

export function ExternalIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={14} height={14}>
      <path d="M14 5h5v5M19 5l-8 8" />
      <path d="M17 13v5a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1h5" />
    </svg>
  );
}

export function SunIcon({ className }: IconProps) {
  return (
    <svg {...base(className)}>
      <circle cx="12" cy="12" r="4.2" />
      <path d="M12 2v2m0 16v2M2 12h2m16 0h2M4.9 4.9l1.4 1.4m11.4 11.4 1.4 1.4M19.1 4.9l-1.4 1.4M6.3 17.7l-1.4 1.4" />
    </svg>
  );
}

export function MoonIcon({ className }: IconProps) {
  return (
    <svg {...base(className)}>
      <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z" />
    </svg>
  );
}

export function PlugIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={20} height={20}>
      <path d="M9 2v6M15 2v6" />
      <path d="M6 8h12v3a6 6 0 0 1-12 0V8Z" />
      <path d="M12 20v2" />
    </svg>
  );
}

export function CopyIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M5 15V5a2 2 0 0 1 2-2h8" />
    </svg>
  );
}

export function CheckIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <path d="m5 12 4.5 4.5L19 7" />
    </svg>
  );
}

export function MenuIcon({ className }: IconProps) {
  return (
    <svg {...base(className)}>
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  );
}

/** One icon set for the sidebar nav, keyed by name — keeps the nav config declarative. */
export function NavIcon({ name, className }: IconProps & { name: string }) {
  const paths: Record<string, JSX.Element> = {
    apis: (
      <>
        <rect x="4" y="4" width="7" height="7" rx="1.5" />
        <rect x="13" y="4" width="7" height="7" rx="1.5" />
        <rect x="4" y="13" width="7" height="7" rx="1.5" />
        <rect x="13" y="13" width="7" height="7" rx="1.5" />
      </>
    ),
    start: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M10 9l5 3-5 3z" />
      </>
    ),
    reference: (
      <>
        <path d="M5 4h11a1 1 0 0 1 1 1v15H6a1 1 0 0 1-1-1z" />
        <path d="M17 20a2 2 0 0 1 2-2V5" />
        <path d="M8 8h6M8 11h6" />
      </>
    ),
    try: (
      <>
        <rect x="3" y="5" width="18" height="14" rx="2" />
        <path d="M7 10l3 2-3 2M13 14h4" />
      </>
    ),
    usage: (
      <>
        <path d="M4 20V4M4 20h16" />
        <path d="M8 16v-3M12 16V8M16 16v-6" />
      </>
    ),
    webhooks: (
      <>
        <path d="M9 8V5M15 8V5" />
        <path d="M7 8h10v2a5 5 0 0 1-10 0z" />
        <path d="M12 15v5" />
      </>
    ),
    changelog: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </>
    ),
    support: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M9.2 9.2a2.8 2.8 0 1 1 3.6 3.9c-.7.4-1 .9-1 1.6" />
        <path d="M12 17h.01" />
      </>
    ),
    credentials: (
      <>
        <circle cx="8" cy="14" r="3.5" />
        <path d="M10.5 11.5L19 3l2 2-2 2-2-2" />
      </>
    ),
    doc: (
      <>
        <path d="M6 3h8l4 4v14H6z" />
        <path d="M14 3v4h4M9 13h6M9 16h6" />
      </>
    ),
    route: (
      <>
        <circle cx="6" cy="6" r="2.2" />
        <circle cx="18" cy="18" r="2.2" />
        <path d="M6 8.2v3.8a4 4 0 0 0 4 4h5.8" />
      </>
    )
  };
  return <svg {...base(className)}>{paths[name] ?? <circle cx="12" cy="12" r="8" />}</svg>;
}
