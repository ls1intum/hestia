import { cn } from "@/lib/utils/utils";
import { SCORE_FILL_CLASS, scoreTier } from "@/lib/grading/score-color";

interface Props {
  /** Fill percentage, 0–100. */
  pct: number;
  /**
   * `primary` — solid brand fill (neutral aggregate bars).
   * `tier` — performance color from the shared `scoreTier` thresholds.
   */
  tone?: "primary" | "tier";
  /** Extra classes on the track (e.g. a width or visibility override). */
  className?: string;
}

/**
 * The thin track + fill progress bar used across the results dashboard. Single
 * source so every score bar stays visually identical; `tone="tier"` routes the
 * fill color through `lib/grading/score-color` instead of the solid brand color.
 */
export const ScoreBar = ({ pct, tone = "primary", className }: Props) => (
  <div
    className={cn(
      "h-1.5 w-full overflow-hidden rounded-full bg-hestia-border/40",
      className,
    )}
  >
    <div
      className={cn(
        "h-full rounded-full transition-all",
        tone === "tier" ? SCORE_FILL_CLASS[scoreTier(pct / 100)] : "bg-hestia-primary",
      )}
      style={{ width: `${pct}%` }}
    />
  </div>
);
