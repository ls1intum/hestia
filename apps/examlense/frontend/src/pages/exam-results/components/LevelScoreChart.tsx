import {
  Bar,
  BarChart,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { SCORE_FILL_HSL, scoreTier } from "@/lib/grading/score-color";

/** One taxonomy-level bucket: the AI's achieved score across its tasks. */
export interface LevelRow {
  key: string;
  label: string;
  count: number;
  earned: number;
  max: number;
  /** Achieved score, 0–100. */
  pct: number;
}

const colorFor = (fraction: number) => SCORE_FILL_HSL[scoreTier(fraction)];

interface TooltipPayload {
  payload: LevelRow;
}

const ChartTooltip = ({
  active,
  payload,
}: {
  active?: boolean;
  payload?: TooltipPayload[];
}) => {
  if (!active || !payload?.length) return null;
  const row = payload[0].payload;
  return (
    <div className="rounded-hestia-md border border-hestia-border bg-hestia-surface px-hestia-2 py-1 text-xs shadow-hestia-sm">
      <div className="font-semibold text-hestia-text">{row.label}</div>
      <div className="tabular-nums text-hestia-text-muted">
        Score: <span className="text-hestia-text">{Number(row.earned.toFixed(2))}</span> / {row.max}
      </div>
      <div className="tabular-nums text-hestia-text-muted">
        {row.pct}% · {row.count} task{row.count === 1 ? "" : "s"}
      </div>
    </div>
  );
};

interface Props {
  title: string;
  rows: LevelRow[];
}

/**
 * The AI's achieved score % grouped by a taxonomy level (Bloom or SOLO).
 * Mirrors TaskScoreBarChart's look; hidden when no levels resolved.
 */
export const LevelScoreChart = ({ title, rows }: Props) => {
  if (rows.length === 0) return null;

  const minWidth = Math.max(rows.length * 72, 320);

  return (
    <div className="hestia-card">
      <div className="mb-hestia-3 flex flex-wrap items-center justify-between gap-hestia-2">
        <h2 className="hestia-eyebrow text-hestia-text-muted">{title}</h2>
        <div className="flex items-center gap-hestia-3 hestia-eyebrow text-hestia-text-muted">
          <LegendDot color={colorFor(1)} label="≥ 80%" />
          <LegendDot color={colorFor(0.5)} label="50–79%" />
          <LegendDot color={colorFor(0)} label="< 50%" />
        </div>
      </div>
      <div className="overflow-x-auto">
        <div style={{ minWidth, height: 220 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
              <XAxis
                dataKey="label"
                tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                tickLine={false}
                axisLine={{ stroke: "hsl(var(--border))" }}
                interval={0}
              />
              <YAxis
                domain={[0, 100]}
                tickFormatter={(v) => `${v}%`}
                tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                tickLine={false}
                axisLine={{ stroke: "hsl(var(--border))" }}
                width={40}
                allowDecimals={false}
              />
              <Tooltip
                cursor={{ fill: "hsl(var(--muted) / 0.4)" }}
                content={<ChartTooltip />}
              />
              <Bar dataKey="pct" radius={[4, 4, 0, 0]} barSize={28}>
                {rows.map((r) => (
                  <Cell key={r.key} fill={colorFor(r.pct / 100)} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      </div>
    </div>
  );
};

const LegendDot = ({ color, label }: { color: string; label: string }) => (
  <span className="inline-flex items-center gap-1">
    <span className="inline-block h-2 w-2 rounded-full" style={{ background: color }} />
    {label}
  </span>
);
