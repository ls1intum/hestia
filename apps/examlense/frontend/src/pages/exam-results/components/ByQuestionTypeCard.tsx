import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { formatScoreSummary, scoreRollup } from "@/lib/grading/grading";
import { TASK_TYPE_LABELS } from "@/lib/exam/labels";
import { RollupRow } from "./RollupRow";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
}

export const ByQuestionTypeCard = ({ tasks, grades, answers }: Props) => {
  const types = ["single_choice", "multiple_choice", "text"] as const;
  const rows = types
    .map((type) => ({
      type,
      ...scoreRollup(
        tasks.filter((tk) => tk.type === type),
        grades,
        answers,
      ),
    }))
    .filter((r) => r.count > 0);

  return (
    <div className="hestia-card">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        By Question Type
      </h2>
      <div className="space-y-hestia-2">
        {rows.map((r) => (
          <RollupRow
            key={r.type}
            label={TASK_TYPE_LABELS[r.type]}
            meta={formatScoreSummary(r)}
            pct={r.pct}
          />
        ))}
      </div>
    </div>
  );
};
