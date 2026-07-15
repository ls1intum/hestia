import { useMemo } from "react";
import {
  ChevronRight,
  GraduationCap,
  ListChecks,
  Table2,
  Target,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { Exam, Task } from "@/lib/exam/exam-helpers";
import type { TaskAnswer, TaskGrade } from "@/lib/grading/grading";
import { goalRollup } from "@/lib/grading/grading";
import { solverModelLabel } from "@/lib/exam/llm-models";
import { useExamLearningGoals } from "@/hooks/data/use-learning-goals";
import { ModelLogo } from "@/components/shared/ModelLogo";
import { ExamResilienceBanner } from "./ExamResilienceBanner";

export type ResultsNavTarget = "learningGoals" | "details" | "allTasks";

interface Props {
  earned: number;
  max: number;
  tasks: Task[];
  grades: Map<string, TaskGrade>;
  answers: Map<string, TaskAnswer>;
  exam: Exam;
  onNavigate: (view: ResultsNavTarget) => void;
}

const NAV_ITEMS: {
  target: ResultsNavTarget;
  label: string;
  hint: string;
  Icon: LucideIcon;
}[] = [
  {
    target: "learningGoals",
    label: "Learning Goals",
    hint: "Per-goal resilience with Bloom & SOLO levels",
    Icon: GraduationCap,
  },
  {
    target: "details",
    label: "Details",
    hint: "By question type, per-task scores & figure impact",
    Icon: Table2,
  },
  {
    target: "allTasks",
    label: "All tasks",
    hint: "Every question with the AI's answer",
    Icon: ListChecks,
  },
];

export const ResilienceOverview = ({
  earned,
  max,
  tasks,
  grades,
  answers,
  exam,
  onNavigate,
}: Props) => {
  const { data: resolvedGoals } = useExamLearningGoals(exam.id);

  // Most-exposed goal = the goal the AI scored highest on (least resilient).
  const weakestGoal = useMemo(() => {
    const rollups = goalRollup(tasks, grades, answers).filter((r) => r.max > 0);
    if (rollups.length === 0) return null;
    const top = rollups.reduce((a, b) => (b.pct > a.pct ? b : a));
    const text =
      (resolvedGoals ?? []).find((g) => g.id === top.goalId)?.text ??
      `Goal #${top.goalId}`;
    return { text, pct: top.pct };
  }, [tasks, grades, answers, resolvedGoals]);

  const modelId =
    exam.solver_model ??
    (answers.values().next().value as TaskAnswer | undefined)?.model ??
    null;
  const modelLabel = modelId ? solverModelLabel(modelId) : null;

  return (
    <div className="space-y-hestia-5">
      <ExamResilienceBanner earned={earned} max={max} />

      {/* At-a-glance highlights */}
      <div
        className={`grid gap-hestia-5 ${weakestGoal && modelId ? "md:grid-cols-2" : ""}`}
      >
        {weakestGoal && (
          <button
            type="button"
            onClick={() => onNavigate("learningGoals")}
            className="hestia-card group flex items-center gap-hestia-4 text-left transition-colors hover:border-hestia-border-strong"
          >
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-hestia-md bg-hestia-danger/10 text-hestia-danger">
              <Target size={18} />
            </div>
            <div className="min-w-0 flex-1">
              <p className="hestia-eyebrow text-hestia-text-muted">
                Most exposed goal
              </p>
              <p className="truncate text-sm font-medium text-hestia-text">
                {weakestGoal.text}
              </p>
              <p className="text-xs text-hestia-text-muted">
                AI scored {weakestGoal.pct}%
              </p>
            </div>
            <ChevronRight
              size={16}
              className="shrink-0 text-hestia-text-muted transition-transform group-hover:translate-x-0.5"
            />
          </button>
        )}

        {modelId && (
          <div className="hestia-card flex items-center gap-hestia-4">
            <div className="flex h-10 w-12 shrink-0 items-center justify-center rounded-hestia-md border border-hestia-border bg-hestia-bg/70 px-1.5 py-1">
              <ModelLogo modelId={modelId} />
            </div>
            <div className="min-w-0 flex-1">
              <p className="hestia-eyebrow text-hestia-text-muted">Tested with</p>
              <p className="truncate text-sm font-medium text-hestia-text">
                {modelLabel}
              </p>
              <p className="text-xs text-hestia-text-muted">
                The AI model that sat this exam
              </p>
            </div>
          </div>
        )}
      </div>

      {/* Where to look next */}
      <div className="hestia-card">
        <h2 className="mb-hestia-3 hestia-eyebrow text-hestia-text-muted">
          Dig deeper
        </h2>
        <div className="space-y-hestia-1">
          {NAV_ITEMS.map(({ target, label, hint, Icon }) => (
            <button
              key={target}
              type="button"
              onClick={() => onNavigate(target)}
              className="group flex w-full items-center gap-hestia-3 rounded-hestia-md px-hestia-2 py-hestia-2 text-left transition-colors hover:bg-hestia-primary-muted/20"
            >
              <Icon size={16} className="shrink-0 text-hestia-text-muted" />
              <span className="text-sm font-medium text-hestia-text">
                {label}
              </span>
              <span className="min-w-0 flex-1 truncate text-xs text-hestia-text-muted">
                {hint}
              </span>
              <ChevronRight
                size={16}
                className="shrink-0 text-hestia-text-muted transition-transform group-hover:translate-x-0.5"
              />
            </button>
          ))}
        </div>
      </div>
    </div>
  );
};
