import { Bot } from "lucide-react";

interface Props {
  earned: number;
  max: number;
}

/**
 * Sits above the resilience banner. Hidden when there are no gradable points —
 * the resilience banner covers that empty state.
 */
export const ExamScorePanel = ({ earned, max }: Props) => {
  if (max <= 0) return null;

  return (
    <div className="hestia-card flex items-center justify-between gap-hestia-4 py-hestia-4">
      <div className="flex min-w-0 items-center gap-hestia-3">
        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-hestia-md bg-hestia-grading/10 text-hestia-grading">
          <Bot size={18} />
        </div>
        <div className="min-w-0">
          <p className="hestia-eyebrow text-hestia-text-muted">Points scored by the AI</p>
          <p className="text-xs text-hestia-text-muted">
            What the AI earned across the whole exam
          </p>
        </div>
      </div>
      <div className="shrink-0 tabular-nums">
        <span className="text-3xl font-bold text-hestia-text">
          {Number(earned.toFixed(2))}
        </span>
        <span className="text-lg text-hestia-text-muted"> / {max}</span>
        <span className="ml-1 text-sm text-hestia-text-muted">points</span>
      </div>
    </div>
  );
};
