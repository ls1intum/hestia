import { useMemo, useState } from "react";
import { ArrowUpDown, ArrowUpRight } from "lucide-react";
import { cn } from "@/lib/utils/utils";
import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { effectiveScore } from "@/lib/grading/grading";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
  labelById: Map<string, string>;
  /** Open this task in the "All tasks" view (jumps to its section + scrolls). */
  onOpenTask: (taskId: string) => void;
}

type SortKey = "label" | "type" | "points" | "score" | "pct";

const Header = ({
  k,
  label,
  onSort,
}: {
  k: SortKey;
  label: string;
  onSort: (k: SortKey) => void;
}) => (
  <button
    type="button"
    onClick={() => onSort(k)}
    className="inline-flex items-center gap-1 hestia-eyebrow text-hestia-text-muted hover:text-hestia-text"
  >
    {label} <ArrowUpDown size={10} />
  </button>
);

export const TaskBreakdownTable = ({ tasks, grades, answers, labelById, onOpenTask }: Props) => {
  const [sortKey, setSortKey] = useState<SortKey>("label");
  const [asc, setAsc] = useState(true);

  const rows = useMemo(() => {
    return tasks.map((tk) => {
      const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
      const pts = tk.points ?? 0;
      const pct = pts > 0 ? Math.round(((eff.score ?? 0) / pts) * 100) : 0;
      return {
        id: tk.id,
        label: labelById.get(tk.id) ?? "",
        type: tk.type,
        points: pts,
        score: eff.score ?? 0,
        pct,
        // Deliberately binary (perfect / zeroed), not the 80/50 performance
        // tiers — a per-task table flags only the tasks the LLM aced (bad,
        // red) and the ones it zeroed (good, green).
        isPerfect: pct === 100,
        isZeroed: pct === 0 && pts > 0,
      };
    });
  }, [tasks, grades, answers, labelById]);

  const sorted = useMemo(() => {
    const copy = [...rows];
    copy.sort((a, b) => {
      const va = a[sortKey];
      const vb = b[sortKey];
      if (typeof va === "number" && typeof vb === "number") return asc ? va - vb : vb - va;
      return asc ? String(va).localeCompare(String(vb)) : String(vb).localeCompare(String(va));
    });
    return copy;
  }, [rows, sortKey, asc]);

  const toggleSort = (key: SortKey) => {
    if (sortKey === key) setAsc((v) => !v);
    else { setSortKey(key); setAsc(true); }
  };

  return (
    <div className="hestia-card overflow-x-auto">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        Task Breakdown
      </h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-hestia-border">
            <th className="pb-2 text-left"><Header k="label" label="Task" onSort={toggleSort} /></th>
            <th className="pb-2 text-left"><Header k="type" label="Type" onSort={toggleSort} /></th>
            <th className="pb-2 text-right"><Header k="score" label="Score" onSort={toggleSort} /></th>
            <th className="pb-2 text-right"><Header k="pct" label="%" onSort={toggleSort} /></th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr
              key={r.id}
              className={cn(
                "border-b border-hestia-border/50",
                r.isPerfect && "bg-hestia-danger/5",
                r.isZeroed && "bg-hestia-success/5",
              )}
            >
              <td className="py-1.5">
                <button
                  type="button"
                  onClick={() => onOpenTask(r.id)}
                  title="Open in All tasks"
                  className="group inline-flex items-center gap-1 font-medium tabular-nums text-hestia-text transition-colors hover:text-hestia-primary"
                >
                  {r.label}
                  <ArrowUpRight
                    size={12}
                    className="text-hestia-text-muted transition-colors group-hover:text-hestia-primary"
                    aria-hidden
                  />
                </button>
              </td>
              <td className="py-1.5 text-hestia-text-muted">{TASK_TYPE_LABELS[r.type]}</td>
              <td className="py-1.5 text-right tabular-nums">
                <span className="font-semibold text-hestia-text">{r.score}</span>
                <span className="text-hestia-text-muted"> / {r.points} points</span>
              </td>
              <td className={cn(
                "py-1.5 text-right tabular-nums font-semibold",
                r.isPerfect && "text-hestia-danger",
                r.isZeroed && "text-hestia-success",
                r.pct > 0 && r.pct < 100 && "text-hestia-text",
              )}>
                {r.pct}%
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};