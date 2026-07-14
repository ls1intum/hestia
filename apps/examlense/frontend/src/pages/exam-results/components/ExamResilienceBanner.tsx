import { ShieldCheck, ShieldAlert, ShieldX, HelpCircle } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  resilienceScore,
  resilienceTier,
  type ResilienceTier,
} from "@/lib/grading/resilience";

interface Props {
  earned: number;
  max: number;
}

const TIER_STYLES: Record<
  ResilienceTier,
  { wrap: string; stroke: string; accent: string; Icon: LucideIcon }
> = {
  safe: {
    wrap: "border-hestia-success/30 bg-hestia-success/10",
    stroke: "hsl(var(--hestia-success))",
    accent: "text-hestia-success",
    Icon: ShieldCheck,
  },
  endangered: {
    wrap: "border-hestia-warning/40 bg-hestia-warning/10",
    stroke: "hsl(var(--hestia-warning))",
    accent: "text-hestia-warning",
    Icon: ShieldAlert,
  },
  critical: {
    wrap: "border-hestia-danger/40 bg-hestia-danger/10",
    stroke: "hsl(var(--hestia-danger))",
    accent: "text-hestia-danger",
    Icon: ShieldX,
  },
};

export const ExamResilienceBanner = ({ earned, max }: Props) => {
  // No gradable points yet — show a neutral, verdict-free state.
  if (max <= 0) {
    return (
      <div className="hestia-card flex flex-col items-center gap-hestia-2 py-hestia-6 text-center">
        <HelpCircle size={22} className="text-hestia-text-muted" />
        <h2 className="text-lg font-bold text-hestia-text">
          Not enough graded data yet
        </h2>
        <p className="max-w-md text-sm text-hestia-text-muted">
          Once this exam's tasks carry points and grades, we'll score how well it
          resists AI assistance.
        </p>
      </div>
    );
  }

  const aiPct = (earned / max) * 100;
  const resilience = resilienceScore(aiPct);
  const verdict = resilienceTier(aiPct);
  const style = TIER_STYLES[verdict.tier];
  const { Icon } = style;

  return (
    <div
      className={`hestia-card flex flex-col items-center gap-hestia-5 border py-hestia-6 sm:flex-row sm:items-center sm:gap-hestia-6 sm:text-left ${style.wrap}`}
    >
      {/* Resilience ring (100 − AI%) */}
      <div className="relative flex h-32 w-32 shrink-0 items-center justify-center">
        <svg viewBox="0 0 36 36" className="h-full w-full -rotate-90">
          <circle
            cx="18"
            cy="18"
            r="15.9"
            fill="none"
            stroke="hsl(var(--border))"
            strokeWidth="2.5"
          />
          <circle
            cx="18"
            cy="18"
            r="15.9"
            fill="none"
            stroke={style.stroke}
            strokeWidth="2.5"
            strokeDasharray={`${resilience} ${100 - resilience}`}
            strokeLinecap="round"
            className="transition-all duration-700"
          />
        </svg>
        <div className="absolute flex flex-col items-center">
          <span className="text-2xl font-bold tabular-nums text-hestia-text">
            {resilience}%
          </span>
          <span className="hestia-eyebrow text-hestia-text-muted">
            Resilience
          </span>
        </div>
      </div>

      {/* Verdict */}
      <div className="flex min-w-0 flex-col items-center gap-hestia-2 sm:items-start">
        <span
          className={`inline-flex items-center gap-hestia-1 hestia-eyebrow ${style.accent}`}
        >
          <Icon size={14} className="shrink-0" />
          {verdict.tierLabel}
        </span>
        <h2 className="text-xl font-bold text-hestia-text sm:text-2xl">
          {verdict.headline}
        </h2>
        <p className="text-sm text-hestia-text-muted">{verdict.blurb}</p>
      </div>
    </div>
  );
};
