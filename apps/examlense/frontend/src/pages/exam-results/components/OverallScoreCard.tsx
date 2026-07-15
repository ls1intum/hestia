interface Props {
  earned: number;
  max: number;
}

export const OverallScoreCard = ({ earned, max }: Props) => {
  const pct = max > 0 ? Math.round((earned / max) * 100) : 0;

  return (
    <div className="hestia-card flex flex-col items-center gap-hestia-3 py-hestia-6">
      <h2 className="hestia-eyebrow text-hestia-text-muted">
        Overall Score
      </h2>
      <div className="relative flex h-32 w-32 items-center justify-center">
        <svg viewBox="0 0 36 36" className="h-full w-full -rotate-90">
          <circle
            cx="18" cy="18" r="15.9"
            fill="none"
            stroke="hsl(var(--border))"
            strokeWidth="2.5"
          />
          <circle
            cx="18" cy="18" r="15.9"
            fill="none"
            stroke="hsl(var(--primary))"
            strokeWidth="2.5"
            strokeDasharray={`${pct} ${100 - pct}`}
            strokeLinecap="round"
            className="transition-all duration-700"
          />
        </svg>
        <span className="absolute text-2xl font-bold tabular-nums text-hestia-text">
          {pct}%
        </span>
      </div>
      <p className="text-lg font-semibold tabular-nums text-hestia-text">
        {earned} <span className="text-hestia-text-muted">/ {max}</span>
      </p>
    </div>
  );
};