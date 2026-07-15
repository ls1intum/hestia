import type { Task, SectionBlock } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { formatScoreSummary, scoreRollup } from "@/lib/grading/grading";
import { RollupRow } from "./RollupRow";

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

  const fig = scoreRollup(withFigures, grades, answers);
  const noFig = scoreRollup(withoutFigures, grades, answers);

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
          <RollupRow
            key={item.label}
            label={item.label}
            meta={formatScoreSummary(item)}
            pct={item.pct}
          />
        ))}
      </div>
    </div>
  );
};
