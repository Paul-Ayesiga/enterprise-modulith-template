// Skeleton placeholders — shown while a page's server data (or an on-demand drill-down) is in flight,
// so the shell paints immediately and the data area shimmers into place instead of popping in blank.
// The shimmer is CSS-only and stilled under prefers-reduced-motion (see .skeleton in globals.css).

/** A single shimmering block. Width/height are inline so callers can shape it to the real content. */
export function Skeleton({ width, height = 14, radius }: { width?: number | string; height?: number | string; radius?: number }) {
  return (
    <span
      className="skeleton"
      style={{ width: width ?? "100%", height, borderRadius: radius }}
      aria-hidden="true"
    />
  );
}

/** A skeleton table body: `rows` × `cols` shimmer cells, sized to look like the table it stands in. */
export function SkeletonTable({ rows = 5, cols = 4 }: { rows?: number; cols?: number }) {
  return (
    <div className="panel panel__scroll" aria-hidden="true">
      <table className="table">
        <tbody>
          {Array.from({ length: rows }).map((_, r) => (
            <tr key={r}>
              {Array.from({ length: cols }).map((_, c) => (
                <td key={c}>
                  <Skeleton width={c === 0 ? "60%" : `${45 + ((r + c) % 4) * 12}%`} />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** A few lines of shimmer, for prose/lede placeholders. */
export function SkeletonLines({ lines = 2, width = "100%" }: { lines?: number; width?: number | string }) {
  return (
    <div style={{ display: "grid", gap: 8 }} aria-hidden="true">
      {Array.from({ length: lines }).map((_, i) => (
        <Skeleton key={i} width={i === lines - 1 ? "70%" : width} />
      ))}
    </div>
  );
}
