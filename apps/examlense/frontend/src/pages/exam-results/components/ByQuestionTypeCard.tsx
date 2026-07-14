import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { effectiveScore } from "@/lib/grading/grading";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
}

export const ByQuestionTypeCard = ({ tasks, grades, answers }: Props) => {
  const types = ["single_choice", "multiple_choice", "text"] as const;
  const rows = types.map((type) => {
    const filtered = tasks.filter((tk) => tk.type === type);
    const max = filtered.reduce((s, tk) => s + (tk.points ?? 0), 0);
    const earned = filtered.reduce((s, tk) => {
      const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
      return s + (eff.score ?? 0);
    }, 0);
    return { type, count: filtered.length, earned, max };
  }).filter((r) => r.count > 0);

  return (
    <div className="hestia-card">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        By Question Type
      </h2>
      <div className="space-y-hestia-2">
        {rows.map((r) => {
          const pct = r.max > 0 ? Math.round((r.earned / r.max) * 100) : 0;
          return (
            <div key={r.type} className="flex items-center justify-between gap-hestia-3">
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline justify-between">
                  <span className="text-sm font-medium text-hestia-text">
                    {TASK_TYPE_LABELS[r.type]}
                  </span>
                  <span className="text-xs tabular-nums text-hestia-text-muted">
                    {r.count} tasks · {r.earned}/{r.max} ({pct}%)
                  </span>
                </div>
                <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-hestia-border/40">
                  <div
                    className="h-full rounded-full bg-hestia-primary transition-all"
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};