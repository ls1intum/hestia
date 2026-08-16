import { useMemo, useState } from "react";
import { ArrowDown, ArrowUp, Target } from "lucide-react";
import type { Task } from "@/lib/exam/exam-helpers";
import type { TaskGrade, TaskAnswer } from "@/lib/grading/grading";
import { formatScoreSummary, goalRollup, scoreRollup } from "@/lib/grading/grading";
import { Badge } from "@/components/ui/badge";
import { useExamLearningGoals } from "@/hooks/data/use-learning-goals";
import type {
  BloomLevel,
  LearningGoalResponse,
  SoloLevel,
} from "@/lib/learning-goals/learning-goals";
import { BLOOM_LABELS, SOLO_LABELS } from "@/lib/exam/labels";
import { ScoreBar } from "./ScoreBar";
import { LevelScoreChart, type LevelRow } from "./LevelScoreChart";

interface Props {
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
  examId: string;
}

/**
 * Group tasks by a taxonomy level derived from their goals (a task is bucketed
 * once per distinct level its goals carry) and score each bucket, in canonical
 * level order, dropping empties.
 */
const levelRows = <L extends string>(
  orderedLevels: L[],
  labels: Record<L, string>,
  levelOf: (goalId: number) => L | null | undefined,
  tasks: Task[],
  grades: Map<string, TaskGrade>,
  answers: Map<string, TaskAnswer>,
): LevelRow[] => {
  const byLevel = new Map<L, Task[]>();
  for (const tk of tasks) {
    const seen = new Set<L>();
    for (const gid of tk.learning_goal_ids ?? []) {
      const lvl = levelOf(gid);
      if (!lvl || seen.has(lvl)) continue;
      seen.add(lvl);
      const list = byLevel.get(lvl);
      if (list) list.push(tk);
      else byLevel.set(lvl, [tk]);
    }
  }
  return orderedLevels.flatMap((lvl) => {
    const bucket = byLevel.get(lvl);
    if (!bucket || bucket.length === 0) return [];
    return [{ key: lvl, label: labels[lvl], ...scoreRollup(bucket, grades, answers) }];
  });
};

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

  const [desc, setDesc] = useState(false);

  const rows = useMemo(
    () =>
      rollups.map(({ goalId, ...metrics }) => ({
        goal:
          goalById.get(goalId) ??
          ({ id: goalId, text: `Goal #${goalId}` } as LearningGoalResponse),
        ...metrics,
      })),
    [rollups, goalById],
  );

  // Sort by achieved score; on a draw, the larger absolute earned score wins
  // (then larger max, for a stable order).
  const sortedRows = useMemo(() => {
    const copy = [...rows];
    copy.sort((a, b) => {
      if (a.pct !== b.pct) return desc ? b.pct - a.pct : a.pct - b.pct;
      if (a.earned !== b.earned) return b.earned - a.earned;
      return b.max - a.max;
    });
    return copy;
  }, [rows, desc]);

  const bloomRows = useMemo(
    () =>
      levelRows(
        Object.keys(BLOOM_LABELS) as BloomLevel[],
        BLOOM_LABELS,
        (gid) => goalById.get(gid)?.bloomLevel,
        tasks,
        grades,
        answers,
      ),
    [goalById, tasks, grades, answers],
  );

  const soloRows = useMemo(
    () =>
      levelRows(
        Object.keys(SOLO_LABELS) as SoloLevel[],
        SOLO_LABELS,
        (gid) => goalById.get(gid)?.soloLevel,
        tasks,
        grades,
        answers,
      ),
    [goalById, tasks, grades, answers],
  );

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

  const unassigned = scoreRollup(
    tasks.filter((tk) => !(tk.learning_goal_ids ?? []).length),
    grades,
    answers,
  );

  return (
    <div className="space-y-hestia-5">
      <LevelScoreChart title="Score by Bloom level" rows={bloomRows} />
      <LevelScoreChart title="Score by SOLO level" rows={soloRows} />

      <div className="hestia-card">
        <div className="mb-hestia-3 flex items-center justify-between gap-hestia-2">
          <h2 className="hestia-eyebrow text-hestia-text-muted">Learning Goals</h2>
          <button
            type="button"
            onClick={() => setDesc((v) => !v)}
            title={desc ? "Highest score first" : "Lowest score first"}
            className="inline-flex items-center gap-1 hestia-eyebrow text-hestia-text-muted transition-colors hover:text-hestia-text"
          >
            Score
            {desc ? <ArrowDown size={12} /> : <ArrowUp size={12} />}
          </button>
        </div>
        {goalsError && (
          <p className="mb-hestia-3 rounded-hestia-md border border-hestia-border bg-hestia-primary-muted/10 px-hestia-3 py-hestia-2 text-xs text-hestia-text-muted">
            Learning goals could not be loaded from LearningGoalHub — showing goal
            ids only.
          </p>
        )}
        <div className="max-h-80 space-y-hestia-4 overflow-y-auto pr-hestia-1">
          {sortedRows.map(({ goal, count, earned, max, pct }) => (
            <div key={goal.id}>
              <div className="flex items-baseline justify-between gap-hestia-3">
                <span className="min-w-0 flex-1 text-sm font-medium text-hestia-text">
                  {goal.text}
                </span>
                <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                  {formatScoreSummary({ count, earned, max, pct })}
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
              <ScoreBar pct={pct} className="mt-hestia-2" />
            </div>
          ))}
        </div>

        {unassigned.count > 0 && (
          <div className="mt-hestia-4 border-t border-hestia-border/60 pt-hestia-3">
            <div className="flex items-baseline justify-between gap-hestia-3">
              <span className="text-sm italic text-hestia-text-muted">
                Unassigned tasks
              </span>
              <span className="shrink-0 text-xs tabular-nums text-hestia-text-muted">
                {formatScoreSummary(unassigned)}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
