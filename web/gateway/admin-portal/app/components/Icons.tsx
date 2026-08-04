// Inline icons. aria-hidden by default — they decorate labelled controls, never stand alone.
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
  return (
    <svg {...base(className)} width={20} height={20}>
      <path d="M4 7v10M20 7v10" />
      <path d="M4 12h6m4 0h6" />
      <circle cx="12" cy="12" r="2.4" fill="currentColor" stroke="none" />
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

export function ChartIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={14} height={14}>
      <path d="M4 20V10M10 20V4M16 20v-7M22 20H2" />
    </svg>
  );
}

export function TrashIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={14} height={14}>
      <path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13" />
    </svg>
  );
}

export function PlusIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={15} height={15}>
      <path d="M12 5v14M5 12h14" />
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

export function PencilIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <path d="M17 3a2.4 2.4 0 0 1 3.4 3.4L8 18.8 3.5 20l1.2-4.5Z" />
    </svg>
  );
}

export function PauseIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <path d="M9 5v14M15 5v14" />
    </svg>
  );
}

export function PlayIcon({ className }: IconProps) {
  return (
    <svg {...base(className)} width={13} height={13}>
      <path d="M7 4.5v15l12-7.5Z" />
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

export function KebabIcon({ className }: IconProps) {
  return (
    <svg {...base(className)}>
      <circle cx="12" cy="5" r="1.4" />
      <circle cx="12" cy="12" r="1.4" />
      <circle cx="12" cy="19" r="1.4" />
    </svg>
  );
}

/** One icon set for the sidebar nav, keyed by name — keeps the nav config declarative. */
export function NavIcon({ name, className }: IconProps & { name: string }) {
  const paths: Record<string, JSX.Element> = {
    overview: (
      <>
        <path d="M4 15a8 8 0 0 1 16 0" />
        <path d="M12 15l4-3" />
      </>
    ),
    routes: (
      <>
        <circle cx="6" cy="6" r="2" />
        <circle cx="18" cy="18" r="2" />
        <path d="M6 8v4a4 4 0 0 0 4 4h6" />
      </>
    ),
    consumers: (
      <>
        <circle cx="9" cy="9" r="3" />
        <path d="M4 20a5 5 0 0 1 10 0" />
        <path d="M16 6.5a3 3 0 0 1 0 5.5" />
        <path d="M20 20a5 5 0 0 0-3-4.6" />
      </>
    ),
    blocklist: <path d="M12 3l7 3v5c0 4.5-3 7.5-7 9-4-1.5-7-4.5-7-9V6l7-3z" />,
    endpoints: (
      <>
        <path d="M9 6l-4 6 4 6M15 6l4 6-4 6" />
      </>
    ),
    services: (
      <>
        <rect x="4" y="4" width="16" height="6" rx="1.5" />
        <rect x="4" y="14" width="16" height="6" rx="1.5" />
        <path d="M7.5 7h.01M7.5 17h.01" />
      </>
    )
  };
  return <svg {...base(className)}>{paths[name] ?? <circle cx="12" cy="12" r="8" />}</svg>;
}
