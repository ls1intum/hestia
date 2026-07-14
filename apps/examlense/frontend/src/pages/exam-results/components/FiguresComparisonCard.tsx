import type { Task, SectionBlock } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { effectiveScore } from "@/lib/grading/grading";

interface Props {
  tasks: Task[];
  blocks: SectionBlock[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
}

export const FiguresComparisonCard = ({ tasks, blocks, grades, answers }: Props) => {
  // Find section IDs that contain at least one figure block
  const sectionsWithFigures = new Set(
    blocks.filter((b) => b.kind === "figure").map((b) => b.section_id),
  );

  const withFigures = tasks.filter((tk) => tk.section_id && sectionsWithFigures.has(tk.section_id));
  const withoutFigures = tasks.filter((tk) => !tk.section_id || !sectionsWithFigures.has(tk.section_id));

  const calc = (list: Task[]) => {
    const max = list.reduce((s, tk) => s + (tk.points ?? 0), 0);
    const earned = list.reduce((s, tk) => {
      const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
      return s + (eff.score ?? 0);
    }, 0);
    return { count: list.length, earned, max, pct: max > 0 ? Math.round((earned / max) * 100) : 0 };
  };

  const fig = calc(withFigures);
  const noFig = calc(withoutFigures);

  if (fig.count === 0 || noFig.count === 0) return null;

  const items = [
    { label: "With figures", ...fig },
    { label: "Without figures", ...noFig },
  ];

  return (
    <div className="hestia-card">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        Figures vs. No Figures
      </h2>
      <div className="space-y-hestia-2">
        {items.map((item) => (
          <div key={item.label} className="flex items-center justify-between gap-hestia-3">
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline justify-between">
                <span className="text-sm font-medium text-hestia-text">{item.label}</span>
                <span className="text-xs tabular-nums text-hestia-text-muted">
                  {item.count} tasks · {item.earned}/{item.max} ({item.pct}%)
                </span>
              </div>
              <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-hestia-border/40">
                <div
                  className="h-full rounded-full bg-hestia-primary transition-all"
                  style={{ width: `${item.pct}%` }}
                />
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};