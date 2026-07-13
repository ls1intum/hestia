import { useMemo } from "react";
import { Target } from "lucide-react";
import type { Task } from "@/lib/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading";
import { goalRollup, scoreRollup } from "@/lib/grading";
import { Badge } from "@/components/ui/badge";
import { useExamLearningGoals } from "@/hooks/use-learning-goals";
import type { LearningGoalResponse } from "@/lib/learning-goals";
import { BLOOM_LABELS, SOLO_LABELS } from "@/lib/labels";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
  examId: string;
}

export const LearningGoalsCard = ({ tasks, grades, answers, examId }: Props) => {
  const { data: resolvedGoals, isError: goalsError } = useExamLearningGoals(examId);

  // The goal ids live on our tasks, so the per-goal metrics work even when
  // LearningGoalHub is unreachable — only the goal texts degrade to "Goal #id".
  const rollups = useMemo(
    () => goalRollup(tasks, grades, answers),
    [tasks, grades, answers],
  );

  const goalById = useMemo(() => {
    const m = new Map<number, LearningGoalResponse>();
    (resolvedGoals ?? []).forEach((g) => m.set(g.id, g));
    return m;
  }, [resolvedGoals]);

  if (rollups.length === 0) {
    return (
      <div className="hestia-card flex flex-col gap-hestia-3 py-hestia-6">
        <h2 className="hestia-eyebrow text-hestia-text-muted">
          Learning Goals
        </h2>
        <div className="flex flex-1 flex-col items-center justify-center gap-hestia-2 rounded-hestia-md border border-dashed border-hestia-border bg-hestia-primary-muted/5 px-hestia-4 py-hestia-6 text-center">
          <Target size={20} className="text-hestia-text-muted" />
          <p className="text-sm text-hestia-text-muted">
            No learning goals were derived for this exam — link a
            LearningGoalHub course and confirm sections to generate them.
          </p>
        </div>
      </div>
    );
  }

  const rows = rollups.map(({ goalId, ...metrics }) => ({
    goal:
      goalById.get(goalId) ??
      ({ id: goalId, text: `Goal #${goalId}` } as LearningGoalResponse),
    ...metrics,
  }));

  const unassigned = scoreRollup(
    tasks.filter((tk) => !(tk.learning_goal_ids ?? []).length),
    grades,
    answers,
  );

  return (
    <div className="hestia-card">
      <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
        Learning Goals
      </h2>
      {goalsError && (
        <p className="mb-hestia-3 rounded-hestia-md border border-hestia-border bg-hestia-primary-muted/10 px-hestia-3 py-hestia-2 text-xs text-hestia-text-muted">
          Learning goals could not be loaded from LearningGoalHub — showing goal
          ids only.
        </p>
      )}
      <div className="space-y-hestia-4">
        {rows.map(({ goal, count, earned, max, pct }) => (
          <div key={goal.id}>
            <div className="flex items-baseline justify-between gap-hestia-3">
              <span className="min-w-0 flex-1 text-sm font-medium text-hestia-text">
                {goal.text}
              </span>
              <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                {count} tasks · {earned}/{max} ({pct}%)
              </span>
            </div>
            <div className="mt-1 flex flex-wrap items-center gap-hestia-1">
              {goal.bloomLevel && (
                <Badge variant="secondary" className="bg-hestia-primary-muted/40 text-[10px] text-hestia-text">
                  {BLOOM_LABELS[goal.bloomLevel]}
                </Badge>
              )}
              {goal.soloLevel && (
                <Badge variant="outline" className="text-[10px] text-hestia-text-muted">
                  {SOLO_LABELS[goal.soloLevel]}
                </Badge>
              )}
            </div>
            <div className="mt-hestia-2 h-1.5 w-full overflow-hidden rounded-full bg-hestia-border/40">
              <div
                className="h-full rounded-full bg-hestia-primary transition-all"
                style={{ width: `${pct}%` }}
              />
            </div>
          </div>
        ))}

        {unassigned.count > 0 && (
          <div className="border-t border-hestia-border/60 pt-hestia-3">
            <div className="flex items-baseline justify-between gap-hestia-3">
              <span className="text-sm italic text-hestia-text-muted">
                Unassigned tasks
              </span>
              <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                {unassigned.count} tasks · {unassigned.earned}/{unassigned.max} ({unassigned.pct}%)
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
