import { Progress } from "@/components/ui/progress";
import { cn } from "@/lib/utils/utils";

/**
 * Live parsing progress for the dashboard Progress column: a pulsing status line
 * above a taller bar whose fill carries a looping left→right shine streak
 * (clipped to the filled width). `hint` is the optional remaining-time text.
 *
 * `barClassName` themes the bar (track tint + fill via a `[&>div]:` override of
 * the Progress indicator); defaults to the success tint used for parsing.
 */
export const ParsingProgress = ({
  label,
  percent,
  hint,
  barClassName = "bg-hestia-success/10",
}: {
  label: string;
  percent: number;
  hint?: string;
  barClassName?: string;
}) => (
  <div
    // min-h + centering keeps the parsing state the same row height as the
    // taller Score→Grade stepper, so rows don't jump on parse start/finish.
    className="flex min-h-[50px] w-full flex-col justify-center gap-1.5"
    title={hint ? `${label} · ${hint}` : label}
  >
    <div className="flex animate-pulse items-center justify-between gap-1 text-[11px] text-hestia-text-muted">
      <span className="min-w-0 truncate">{label}</span>
      {hint && <span className="shrink-0 tabular-nums">{hint}</span>}
    </div>
    <div className="relative">
      <Progress value={percent} className={cn("h-2.5", barClassName)} />
      {/* Shine streak, clipped to the filled portion of the bar. */}
      <div
        className="pointer-events-none absolute inset-y-0 left-0 overflow-hidden rounded-full"
        style={{ width: `${percent}%` }}
      >
        <div className="absolute inset-y-0 w-1/2 animate-progress-shimmer-loop bg-gradient-to-r from-transparent via-white/30 to-transparent" />
      </div>
    </div>
  </div>
);
