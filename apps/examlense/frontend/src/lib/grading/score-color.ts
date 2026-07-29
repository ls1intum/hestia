/**
 * Shared performance-color thresholds for score visualizations in the results
 * dashboard. `pct` is a fraction (0–1). Single source of truth so the
 * Score-per-Task chart and the per-task score bars stay in sync.
 *
 * Polarity is inverted relative to a normal gradebook: the value shown is the
 * LLM's achieved score, and a HIGH LLM score is bad (the exam is exploitable),
 * so it renders red — while a LOW LLM score is good (resilient) and renders
 * green.
 */

export type ScoreTier = "high" | "mid" | "low";

export const scoreTier = (pct: number): ScoreTier =>
  pct >= 0.8 ? "high" : pct >= 0.5 ? "mid" : "low";

/** Tailwind background classes for HTML progress-bar fills. */
export const SCORE_FILL_CLASS: Record<ScoreTier, string> = {
  high: "bg-hestia-danger",
  mid: "bg-hestia-warning",
  low: "bg-hestia-success",
};

/** Color strings for SVG fills (recharts `<Cell fill>`, legend dots). */
export const SCORE_FILL_HSL: Record<ScoreTier, string> = {
  high: "hsl(var(--hestia-danger))",
  mid: "hsl(var(--hestia-warning))",
  low: "hsl(var(--hestia-success))",
};
