import { CONNECTOR_H, rowWidth, childCentres } from "./layout.ts";

/**
 * Edge fade with a scroll arrow, shown only on a side the map overflows. It stays beneath the
 * pinned topic row and the grid header, and its arrow sticks to the middle of the visible part of
 * a diagram taller than the view.
 */
export function ScrollEdge({
  side,
  onClick,
}: {
  side: "left" | "right";
  onClick: () => void;
}) {
  const left = side === "left";
  return (
    <div
      className={`pointer-events-none absolute inset-y-0 z-[4] flex w-14 items-start py-4 ${
        left ? "left-0 justify-start pl-1" : "right-0 justify-end pr-1"
      }`}
      style={{
        background: `linear-gradient(to ${left ? "right" : "left"}, var(--hestia-bg), color-mix(in srgb, var(--hestia-bg) 80%, transparent), transparent)`,
      }}
    >
      <button
        type="button"
        aria-label={`Scroll topic diagram ${side}`}
        className="pointer-events-auto sticky top-1/2 rounded-full border border-hestia-border bg-hestia-bg/90 p-1 text-hestia-text-muted shadow-sm transition hover:bg-hestia-surface hover:text-hestia-text"
        onClick={onClick}
      >
        <svg
          viewBox="0 0 20 20"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
          className="h-4 w-4"
        >
          <path d={left ? "M12.5 4.5 7 10l5.5 5.5" : "m7.5 4.5 5.5 5.5-5.5 5.5"} />
        </svg>
      </button>
    </div>
  );
}

/**
 * Tree connector between a centred parent and its children's row. The trunk drops from the row's
 * midpoint, turns onto a horizontal rail and drops a stub to each child — the standard tree
 * routing, mirrored for children left of the trunk; joins are rounded so there are no sharp
 * corners. Child centres are derived from their explicit widths, so compact siblings and leaf
 * boxes remain aligned without DOM measuring.
 */
export function Connector({
  childWidths,
  color,
  focusedIndex,
}: {
  childWidths: number[];
  color: string;
  /** Dims every link but the one to the focused child. */
  focusedIndex?: number;
}) {
  const count = childWidths.length;
  if (count === 0) return null;
  const width = rowWidth(childWidths);
  const centres = childCentres(childWidths);
  const trunk = width / 2;
  const mid = CONNECTOR_H / 2;
  const r = 8; // corner radius for the rounded joins

  // `pathLength={1}` normalises every path so the comp-draw dash animation can draw each link
  // from the trunk toward its child, whatever the actual path length.
  return (
    <svg
      width={width}
      height={CONNECTOR_H}
      className="comp-draw block shrink-0"
      aria-hidden="true"
    >
      <g stroke={color} strokeWidth={1.5} fill="none" strokeLinecap="round">
        {Array.from({ length: count }, (_, i) => {
          const cx = centres[i];
          const dx = cx - trunk;
          const dimmed =
            focusedIndex != null && i !== focusedIndex ? 0.38 : undefined;
          // Too close to the trunk for the rail-and-corners route: a gentle S-curve instead
          // (straight drop when the child sits exactly under the trunk).
          if (Math.abs(dx) < 2 * r) {
            return (
              <path
                key={i}
                pathLength={1}
                opacity={dimmed}
                d={`M ${trunk} 0 C ${trunk} ${mid} ${cx} ${mid} ${cx} ${CONNECTOR_H}`}
              />
            );
          }
          const sg = dx > 0 ? 1 : -1;
          return (
            <path
              key={i}
              pathLength={1}
              opacity={dimmed}
              d={`M ${trunk} 0 V ${mid - r} Q ${trunk} ${mid} ${trunk + sg * r} ${mid} H ${cx - sg * r} Q ${cx} ${mid} ${cx} ${mid + r} V ${CONNECTOR_H}`}
            />
          );
        })}
      </g>
    </svg>
  );
}
