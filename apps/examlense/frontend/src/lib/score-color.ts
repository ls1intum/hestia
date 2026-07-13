/**
 * Shared performance-color thresholds for score visualizations in the results
 * dashboard. `pct` is a fraction (0–1). Single source of truth so the
 * Score-per-Task chart and the per-task score bars stay in sync.
 */

export type ScoreTier = "high" | "mid" | "low";

export const scoreTier = (pct: number): ScoreTier =>
  pct >= 0.8 ? "high" : pct >= 0.5 ? "mid" : "low";

/** Tailwind background classes for HTML progress-bar fills. */
export const SCORE_FILL_CLASS: Record<ScoreTier, string> = {
  high: "bg-hestia-success",
  mid: "bg-hestia-warning",
  low: "bg-hestia-danger",
};

/** Color strings for SVG fills (recharts `<Cell fill>`, legend dots). */
export const SCORE_FILL_HSL: Record<ScoreTier, string> = {
  high: "hsl(var(--hestia-success))",
  mid: "hsl(var(--hestia-warning))",
  low: "hsl(var(--hestia-danger))",
};
