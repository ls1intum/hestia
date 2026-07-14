import { useMemo } from "react";
import {
  Bar,
  BarChart,
  Cell,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import type { Task } from "@/lib/exam/exam-helpers";
import { effectiveScore, type TaskAnswer, type TaskGrade } from "@/lib/grading/grading";
import { SCORE_FILL_HSL, scoreTier } from "@/lib/grading/score-color";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
  labelById: Map<string, string>;
}

interface Row {
  id: string;
  label: string;
  earned: number;
  max: number;
  pct: number;
}

const colorFor = (pct: number) => SCORE_FILL_HSL[scoreTier(pct)];

interface TooltipPayload {
  payload: Row;
}

const ChartTooltip = ({
  active,
  payload,
  maxLabel,
  scoreLabel,
}: {
  active?: boolean;
  payload?: TooltipPayload[];
  maxLabel: string;
  scoreLabel: string;
}) => {
  if (!active || !payload?.length) return null;
  const row = payload[0].payload;
  return (
    <div className="rounded-hestia-md border border-hestia-border bg-hestia-surface px-hestia-2 py-1 text-xs shadow-hestia-sm">
      <div className="font-semibold text-hestia-text">{row.label}</div>
      <div className="tabular-nums text-hestia-text-muted">
        {scoreLabel}: <span className="text-hestia-text">{row.earned}</span>
      </div>
      <div className="tabular-nums text-hestia-text-muted">
        {maxLabel}: <span className="text-hestia-text">{row.max}</span>
      </div>
      <div className="tabular-nums text-hestia-text-muted">
        {Math.round(row.pct * 100)}%
      </div>
    </div>
  );
};

export const TaskScoreBarChart = ({ tasks, grades, answers, labelById }: Props) => {
  const rows = useMemo<Row[]>(() => {
    const sorted = tasks
      .slice()
      .sort((a, b) => {
        const la = labelById.get(a.id) ?? "";
        const lb = labelById.get(b.id) ?? "";
        return la.localeCompare(lb, undefined, { numeric: true });
      });
    return sorted.map((tk) => {
      const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
      const max = tk.points ?? 0;
      const earned = eff.score ?? 0;
      return {
        id: tk.id,
        label: labelById.get(tk.id) ?? "",
        earned,
        max,
        pct: max > 0 ? earned / max : 0,
      };
    });
  }, [tasks, grades, answers, labelById]);

  if (rows.length === 0) return null;

  const minWidth = Math.max(rows.length * 56, 320);
  const maxPoints = rows.reduce((acc, r) => Math.max(acc, r.max), 0);

  return (
    <div className="hestia-card">
      <div className="mb-hestia-3 flex flex-wrap items-center justify-between gap-hestia-2">
        <h2 className="hestia-eyebrow text-hestia-text-muted">
          Score per Task
        </h2>
        <div className="flex items-center gap-hestia-3 hestia-eyebrow text-hestia-text-muted">
          <LegendDot color={colorFor(1)} label="≥ 80%" />
          <LegendDot color={colorFor(0.5)} label="50–79%" />
          <LegendDot color={colorFor(0)} label="< 50%" />
        </div>
      </div>
      <div className="overflow-x-auto">
        <div style={{ minWidth, height: 240 }}>
          <ResponsiveContainer width="100%" height="100%">
            <BarChart
              data={rows}
              barGap={-20}
              margin={{ top: 8, right: 8, bottom: 8, left: 0 }}
            >
              <XAxis
                dataKey="label"
                tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                tickLine={false}
                axisLine={{ stroke: "hsl(var(--border))" }}
                interval={0}
              />
              <YAxis
                domain={[0, maxPoints || 1]}
                tick={{ fontSize: 11, fill: "hsl(var(--muted-foreground))" }}
                tickLine={false}
                axisLine={{ stroke: "hsl(var(--border))" }}
                width={32}
                allowDecimals={false}
              />
              <Tooltip
                cursor={{ fill: "hsl(var(--muted) / 0.4)" }}
                content={
                  <ChartTooltip
                    maxLabel="Max"
                    scoreLabel="Score"
                  />
                }
              />
              <Bar dataKey="max" fill="hsl(var(--border))" radius={[4, 4, 0, 0]} barSize={20} />
              <Bar dataKey="earned" radius={[4, 4, 0, 0]} barSize={20}>
                {rows.map((r) => (
                  <Cell key={r.id} fill={colorFor(r.pct)} />
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
