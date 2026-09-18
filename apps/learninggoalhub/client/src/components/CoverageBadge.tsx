import InfoTooltip from "./InfoTooltip.tsx";
import { COVERAGE_META, coverageOf, type Coverage } from "../lib/goals.ts";
import type { LearningGoal } from "../api/client.ts";

/**
 * Whether a sub-skill or knowledge item comes from a lecture, an exercise, or both — read off its
 * sources. Renders nothing for a goal whose documents carry no kind. A level mismatch against the
 * topic's lectures shows as a quiet warning icon with the reason as a tooltip.
 */
export default function CoverageBadge({
  goal,
  flag,
  size = "small",
}: {
  goal: LearningGoal;
  /** Why this sub-skill's exercise level looks abnormal, if it does. */
  flag?: string;
  /** "small" for a map box; "cell" matches the other pills of a table column. */
  size?: PillSize;
}) {
  const coverage = coverageOf(goal);
  if (coverage == null || coverage === "unknown") return null;
  return (
    <span className="inline-flex shrink-0 items-center gap-1 align-middle">
      <CoveragePill coverage={coverage} size={size} />
      {flag && (
        // Reading the warning is not a click on the row it sits in.
        <span className="inline-flex" onClick={(e) => e.stopPropagation()}>
          <InfoTooltip
            content={<p className="text-xs text-hestia-text">{flag}</p>}
            className="inline-flex text-hestia-warning"
          >
            <svg
              viewBox="0 0 20 20"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              role="img"
              aria-label={flag}
              tabIndex={0}
              className="h-3.5 w-3.5 outline-none"
            >
              <path d="M10 3.5l7 12.5H3z" />
              <path d="M10 8.5v3.5M10 14.2v.01" />
            </svg>
          </InfoTooltip>
        </span>
      )}
    </span>
  );
}

type PillSize = "small" | "cell";

const PILL_SIZE: Record<PillSize, string> = {
  small: "px-1.5 text-[10px] leading-4 uppercase tracking-wide",
  cell: "px-2 py-0.5 text-xs",
};

/** A coverage label in its colour. */
function CoveragePill({
  coverage,
  size,
}: {
  coverage: Exclude<Coverage, "unknown">;
  size: PillSize;
}) {
  const meta = COVERAGE_META[coverage];
  return (
    <span
      className={`inline-flex items-center whitespace-nowrap rounded-full font-semibold ${PILL_SIZE[size]}`}
      style={{
        color: meta.color,
        backgroundColor: `color-mix(in srgb, ${meta.color} 15%, transparent)`,
      }}
    >
      {meta.label}
    </span>
  );
}

