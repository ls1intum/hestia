import { useEffect, useRef, useState } from "react";
import { Loader2, Sparkles, AlertTriangle, PenLine } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Slider } from "@/components/ui/slider";
import { useQueryClient } from "@tanstack/react-query";
import { useUpsertTaskGrade } from "@/hooks/use-task-grades";
import { taskAnswersKey } from "@/hooks/use-task-answers";
import { autoGradeChoiceTask, type TaskAnswer, type TaskGrade } from "@/lib/grading";
import { MarkdownView } from "../MarkdownView";
import type { Task } from "@/lib/exam-helpers";
import { cn } from "@/lib/utils";
import { solveTask } from "@/lib/api-solve";
import { GRADE_SOURCE_LABELS } from "@/lib/labels";

const preventNumberWheelChange = (event: React.WheelEvent<HTMLInputElement>) => {
  event.currentTarget.blur();
};

interface Props {
  task: Task;
  examId: string;
  answer: TaskAnswer | undefined;
  grade: TaskGrade | undefined;
}

export const TaskGradingPanel = ({ task, examId, answer, grade }: Props) => {
  const qc = useQueryClient();
  const upsert = useUpsertTaskGrade(examId);

  const auto = autoGradeChoiceTask(task, answer);
  const initialScore =
    grade?.score != null
      ? String(grade.score)
      : auto != null
        ? String(auto.score)
        : "";
  const [scoreStr, setScoreStr] = useState(initialScore);
  useEffect(() => {
    setScoreStr(
      grade?.score != null
        ? String(grade.score)
        : auto != null
          ? String(auto.score)
          : "",
    );
  }, [grade?.score, auto?.score]);

  const persist = (nextScore: number | null, autoFlag: boolean) => {
    upsert.mutate({
      task_id: task.id,
      exam_id: examId,
      score: nextScore,
      auto_graded: autoFlag,
      feedback: null,
    });
  };

  const onScoreBlur = () => {
    if (scoreStr === "") {
      persist(null, false);
      return;
    }
    let score = Number(scoreStr);
    if (!Number.isFinite(score)) return;
    score = Math.max(0, score);
    if (task.points != null) score = Math.min(score, task.points);
    setScoreStr(String(score));
    persist(score, false);
  };

  // Radix Slider only fires onValueChange/onValueCommit when the value actually
  // changes. For an ungraded task the thumb already sits at 0, so committing a
  // score of 0 by clicking at 0 is a no-op. Track whether the value changed during
  // a pointer interaction and force a single commit on release when it didn't.
  const changedDuringSlideRef = useRef(false);

  const sliderMax = task.points ?? 0;
  const sliderValue = (() => {
    const n = Number(scoreStr);
    if (!Number.isFinite(n)) return 0;
    return Math.min(Math.max(n, 0), sliderMax);
  })();

  // Per-card generation state (no answer yet)
  const [generating, setGenerating] = useState(false);
  const [genError, setGenError] = useState<string | null>(null);

  const generate = async () => {
    setGenerating(true);
    setGenError(null);
    try {
      await solveTask(task.id);
      await qc.invalidateQueries({ queryKey: taskAnswersKey(examId) });
    } catch (err) {
      setGenError(err instanceof Error ? err.message : "Could not generate an answer.");
    } finally {
      setGenerating(false);
    }
  };

  const source: "auto" | "manual" | "pending" =
    grade?.score != null
      ? grade.auto_graded
        ? "auto"
        : "manual"
      : auto != null
        ? "auto"
        : "pending";

  // Card content depends on whether the LLM produced an answer.
  const noAnswer = !answer;

  return (
    <section
      className={cn(
        "relative rounded-hestia-md border-2 border-hestia-primary/30 bg-hestia-primary-muted/5 p-hestia-3",
        generating && "pointer-events-none",
      )}
      aria-busy={generating}
    >
      {generating && (
        <div className="absolute inset-0 z-10 flex items-center justify-center rounded-hestia-md bg-hestia-bg/70 backdrop-blur-sm">
          <Loader2 className="h-5 w-5 animate-spin text-hestia-primary" />
        </div>
      )}

      <div className="mb-hestia-2 flex items-center justify-between gap-hestia-2">
        <div className="flex items-center gap-hestia-2">
          <PenLine size={12} className="text-hestia-primary" />
          <span className="hestia-eyebrow text-hestia-text-muted">
            Grade
          </span>
        </div>
        <Badge
          variant="secondary"
          className={cn(
            "shrink-0",
            source === "manual" && "bg-hestia-primary/15 text-hestia-primary",
            source === "auto" && "bg-hestia-success/15 text-hestia-success",
            source === "pending" && "bg-hestia-danger/10 text-hestia-danger",
          )}
        >
          {GRADE_SOURCE_LABELS[source]}
        </Badge>
      </div>

      {noAnswer ? (
        <div className="space-y-hestia-2">
          <p className="text-sm text-hestia-text-muted">No answer was generated for this task.</p>
          {genError && (
            <p className="flex items-center gap-1 text-xs text-hestia-danger">
              <AlertTriangle size={12} />
              {genError}
            </p>
          )}
          <button
            type="button"
            onClick={generate}
            disabled={generating}
            className="inline-flex items-center gap-2 rounded-hestia-md bg-hestia-primary px-hestia-3 py-1.5 text-xs font-medium text-white shadow-sm transition-colors hover:bg-hestia-primary/90 disabled:opacity-60"
          >
            <Sparkles size={12} />
            {genError ? "Try again" : "Generate answer"}
          </button>
        </div>
      ) : task.type !== "text" ? (
        <p className="text-sm text-hestia-text-muted">
          See selected options above.
        </p>
      ) : (
        <div className="space-y-hestia-2">
          <div className="rounded-hestia-sm border border-hestia-border/60 bg-hestia-surface px-hestia-2 py-hestia-2">
            <MarkdownView content={answer!.answer_text ?? ""} />
          </div>
          {answer?.reasoning && (
            <details className="text-xs text-hestia-text-muted">
              <summary className="cursor-pointer select-none">
                Show reasoning
              </summary>
              <div className="mt-1">
                <MarkdownView
                  content={answer.reasoning}
                  className="text-hestia-text-muted"
                />
              </div>
            </details>
          )}
        </div>
      )}

      <div className="mt-hestia-3 border-t border-hestia-border pt-hestia-3">
        <span className="hestia-eyebrow text-hestia-text-muted">
          {`Score (max ${task.points ?? "?"})`}
        </span>
        <div
          className={cn(
            "mt-1 flex flex-wrap items-center gap-hestia-3 rounded-hestia-sm border bg-transparent p-hestia-2",
            grade?.score == null
              ? source === "auto"
                ? "border-hestia-warning ring-1 ring-hestia-warning/30"
                : "border-hestia-danger ring-1 ring-hestia-danger/30"
              : "border-transparent",
          )}
        >
          {task.points != null && (
            <div className="flex h-9 min-w-[160px] flex-1 items-center">
              <Slider
                min={0}
                max={sliderMax}
                step={0.25}
                value={[sliderValue]}
                disabled={noAnswer}
                onPointerDown={() => {
                  changedDuringSlideRef.current = false;
                }}
                onValueChange={(v) => {
                  changedDuringSlideRef.current = true;
                  setScoreStr(String(v[0]));
                }}
                onValueCommit={(v) => persist(v[0], false)}
                onPointerUp={() => {
                  if (!changedDuringSlideRef.current) persist(sliderValue, false);
                }}
              />
            </div>
          )}
          <Input
            type="number"
            min={0}
            max={task.points ?? undefined}
            step={0.5}
            value={scoreStr}
            onWheel={preventNumberWheelChange}
            onChange={(e) => setScoreStr(e.target.value)}
            onBlur={onScoreBlur}
            className="h-9 w-24 border-hestia-border bg-hestia-surface text-sm"
          />
        </div>
      </div>
    </section>
  );
};
