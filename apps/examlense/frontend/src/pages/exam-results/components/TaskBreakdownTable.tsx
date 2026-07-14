import { useMemo, useState } from "react";
import { ArrowUpDown } from "lucide-react";
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
}

type SortKey = "label" | "type" | "points" | "score" | "pct";

export const TaskBreakdownTable = ({ tasks, grades, answers, labelById }: Props) => {
  const [sortKey, setSortKey] = useState<SortKey>("label");
  const [asc, setAsc] = useState(true);

  const rows = useMemo(() => {
    return tasks.map((tk) => {
      const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
      const pts = tk.points ?? 0;
      return {
        id: tk.id,
        label: labelById.get(tk.id) ?? "",
        type: tk.type,
        points: pts,
        score: eff.score ?? 0,
        pct: pts > 0 ? Math.round(((eff.score ?? 0) / pts) * 100) : 0,
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

  const Header = ({ k, label }: { k: SortKey; label: string }) => (
    <button
      type="button"
      onClick={() => toggleSort(k)}
      className="inline-flex items-center gap-1 hestia-eyebrow text-hestia-text-muted hover:text-hestia-text"
    >
      {label} <ArrowUpDown size={10} />
    </button>
  );

  return (
    <div className="hestia-card overflow-x-auto">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        Task Breakdown
      </h2>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-hestia-border">
            <th className="pb-2 text-left"><Header k="label" label="Task" /></th>
            <th className="pb-2 text-left"><Header k="type" label="Type" /></th>
            <th className="pb-2 text-right"><Header k="points" label="Max" /></th>
            <th className="pb-2 text-right"><Header k="score" label="Score" /></th>
            <th className="pb-2 text-right"><Header k="pct" label="%" /></th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((r) => (
            <tr
              key={r.id}
              className={cn(
                "border-b border-hestia-border/50",
                r.pct === 100 && "bg-hestia-success/5",
                r.pct === 0 && r.points > 0 && "bg-hestia-danger/5",
              )}
            >
              <td className="py-1.5 font-medium tabular-nums text-hestia-text">{r.label}</td>
              <td className="py-1.5 text-hestia-text-muted">{TASK_TYPE_LABELS[r.type]}</td>
              <td className="py-1.5 text-right tabular-nums text-hestia-text-muted">{r.points}</td>
              <td className="py-1.5 text-right tabular-nums font-semibold text-hestia-text">{r.score}</td>
              <td className={cn(
                "py-1.5 text-right tabular-nums font-semibold",
                r.pct === 100 && "text-hestia-success",
                r.pct === 0 && r.points > 0 && "text-hestia-danger",
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