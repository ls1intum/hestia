import type { ReactNode } from "react";
import { ScoreBar } from "./ScoreBar";

interface Props {
  label: ReactNode;
  /** Right-aligned summary text — see `formatScoreSummary`. */
  meta: string;
  /** Percentage 0–100 for the bar. */
  pct: number;
}

/**
 * One "label · summary + progress bar" row, shared by the aggregate results
 * cards (by question type, figures vs. no figures) so their layout can't drift.
 */
export const RollupRow = ({ label, meta, pct }: Props) => (
  <div className="flex items-center justify-between gap-hestia-3">
    <div className="min-w-0 flex-1">
      <div className="flex items-baseline justify-between">
        <span className="text-sm font-medium text-hestia-text">{label}</span>
        <span className="text-xs tabular-nums text-hestia-text-muted">{meta}</span>
      </div>
      <ScoreBar pct={pct} className="mt-1" />
    </div>
  </div>
);
