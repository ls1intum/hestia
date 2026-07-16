import type { Task, TaskOption } from "@/lib/exam/exam-helpers";

export interface TaskAnswer {
  id: string;
  task_id: string;
  exam_id: string;
  selected_option_ids: string[];
  answer_text: string | null;
  reasoning: string | null;
  provider: string;
  model: string;
  created_at: string;
}

export interface TaskGrade {
  id: string;
  task_id: string;
  exam_id: string;
  score: number | null;
  auto_graded: boolean;
  feedback: string | null;
  graded_by: string | null;
  created_at: string;
  updated_at: string;
}

export const correctOptionIds = (task: Task): string[] =>
  (task.options ?? []).filter((o: TaskOption) => o.is_correct).map((o) => o.id);

export interface AutoGradeResult {
  isCorrect: boolean;
  score: number;
}

/**
 * Compute an auto-grade for a single-choice or multiple-choice task.
 *
 * Returns null when the task is not a choice task, no answer exists, the task
 * has no `points` set, or there are no correct options defined (in which case
 * the instructor must grade manually).
 */
export const autoGradeChoiceTask = (
  task: Task,
  answer: TaskAnswer | undefined,
): AutoGradeResult | null => {
  if (task.type === "text") return null;
  if (task.points == null || task.points <= 0) return null;
  const correct = correctOptionIds(task);
  if (correct.length === 0) return null;
  if (!answer) return null;
  const correctSet = new Set(correct);
  const pickedSet = new Set(answer.selected_option_ids ?? []);
  const isCorrect =
    correctSet.size === pickedSet.size &&
    [...correctSet].every((id) => pickedSet.has(id));
  return { isCorrect, score: isCorrect ? task.points : 0 };
};

/** Effective score the UI should display for a task. */
export const effectiveScore = (
  task: Task,
  grade: TaskGrade | undefined,
  answer: TaskAnswer | undefined,
): { score: number | null; source: "manual" | "auto" | "pending" } => {
  if (grade && grade.score != null) {
    return { score: grade.score, source: grade.auto_graded ? "auto" : "manual" };
  }
  const auto = autoGradeChoiceTask(task, answer);
  if (auto) return { score: auto.score, source: "auto" };
  return { score: null, source: "pending" };
};

export const examTotals = (
  tasks: Task[],
  grades: Map<string, TaskGrade>,
  answers: Map<string, TaskAnswer>,
) => {
  let earned = 0;
  let max = 0;
  let pending = 0;
  for (const task of tasks) {
    const points = task.points ?? 0;
    max += points;
    const eff = effectiveScore(task, grades.get(task.id), answers.get(task.id));
    if (eff.score == null) pending += 1;
    else earned += eff.score;
  }
  return { earned, max, pending };
};

/** Earned/max/pct rollup for an arbitrary subset of tasks. */
export const scoreRollup = (
  tasks: Task[],
  grades: Map<string, TaskGrade>,
  answers: Map<string, TaskAnswer>,
) => {
  const max = tasks.reduce((s, tk) => s + (tk.points ?? 0), 0);
  const earned = tasks.reduce((s, tk) => {
    const eff = effectiveScore(tk, grades.get(tk.id), answers.get(tk.id));
    return s + (eff.score ?? 0);
  }, 0);
  return { count: tasks.length, earned, max, pct: max > 0 ? Math.round((earned / max) * 100) : 0 };
};

/** Compact "{count} tasks · {earned}/{max} ({pct}%)" label for a score rollup. */
export const formatScoreSummary = ({
  count,
  earned,
  max,
  pct,
}: {
  count: number;
  earned: number;
  max: number;
  pct: number;
}): string => `${count} tasks · ${earned}/${max} (${pct}%)`;

export interface GoalRollup {
  goalId: number;
  count: number;
  earned: number;
  max: number;
  pct: number;
}

/**
 * Per-learning-goal score rollup, in first-seen order across the tasks. Goal ids
 * live on `tasks.learning_goal_ids`, so this works even when the goal texts
 * can't be resolved from LearningGoalHub.
 */
export const goalRollup = (
  tasks: Task[],
  grades: Map<string, TaskGrade>,
  answers: Map<string, TaskAnswer>,
): GoalRollup[] => {
  const ids: number[] = [];
  const seen = new Set<number>();
  for (const tk of tasks) {
    for (const id of tk.learning_goal_ids ?? []) {
      if (!seen.has(id)) {
        seen.add(id);
        ids.push(id);
      }
    }
  }
  return ids.map((goalId) => ({
    goalId,
    ...scoreRollup(
      tasks.filter((tk) => (tk.learning_goal_ids ?? []).includes(goalId)),
      grades,
      answers,
    ),
  }));
};
