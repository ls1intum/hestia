import { useEffect, useRef, useState } from "react";
import { Loader2, Sparkles, AlertTriangle, Pencil } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Slider } from "@/components/ui/slider";
import { useQueryClient } from "@tanstack/react-query";
import { useUpsertTaskGrade } from "@/hooks/data/use-task-grades";
import { usePatchTask } from "@/hooks/data/use-exam";
import { useClickToEdit } from "@/hooks/ui/use-click-to-edit";
import { taskAnswersKey } from "@/hooks/data/use-task-answers";
import { autoGradeChoiceTask, type TaskAnswer, type TaskGrade } from "@/lib/grading/grading";
import { AiAnswerBlock } from "@/components/shared/exam-content/read-only/AiAnswerBlock";
import { AnswerCard } from "@/components/shared/exam-content/read-only/AnswerCard";
import type { Task } from "@/lib/exam/exam-helpers";
import { cn, preventNumberWheelChange } from "@/lib/utils/utils";
import { solveTask } from "@/lib/api/api-solve";

/** Grading increment shared by the slider and the number input. */
const SCORE_STEP = 0.5;
/** Only draw slider tick marks when the count stays readable. */
const MAX_TICKS = 16;

interface Props {
  task: Task;
  examId: string;
  answer: TaskAnswer | undefined;
  grade: TaskGrade | undefined;
}

/**
 * The gradable "work object" in grading mode: the AI answer (or the selected
 * options for choice tasks) plus the score controls, rendered as a real
 * `primary` card. The static question lives card-less in `ReadOnlyQuestionBlock`
 * above it — so this card, not the question, is the thing the grader acts on.
 */
export const TaskGradingPanel = ({ task, examId, answer, grade }: Props) => {
  const qc = useQueryClient();
  const upsert = useUpsertTaskGrade(examId);
  const patchPoints = usePatchTask(examId);

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
        : auto?.score != null
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

  // Skips empty / invalid / unchanged input so the max never lands in a dead
  // null state. Lowering the max below an already-set grade clamps that grade
  // down too (e.g. 5/5 → editing max to 2 → 2/2).
  const maxEdit = useClickToEdit(
    task.points != null ? String(task.points) : "",
    (next) => {
      const t = next.trim();
      if (t === "") return;
      const n = Number(t);
      if (!Number.isFinite(n) || n < 0 || n === task.points) return;
      patchPoints.mutate({ taskId: task.id, patch: { points: n } });
      if (grade?.score != null && grade.score > n) {
        setScoreStr(String(n));
        persist(n, grade.auto_graded);
      }
    },
  );

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

  const tickCount = sliderMax > 0 ? Math.round(sliderMax / SCORE_STEP) + 1 : 0;
  const showTicks = tickCount > 1 && tickCount <= MAX_TICKS + 1;

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

  const noAnswer = !answer;

  const body = (
    <div className={cn("relative", generating && "pointer-events-none")} aria-busy={generating}>
      {generating && (
        <div className="absolute inset-0 z-10 flex items-center justify-center rounded-hestia-md bg-hestia-bg/70 backdrop-blur-sm">
          <Loader2 className="h-5 w-5 animate-spin text-hestia-primary" />
        </div>
      )}

      {!answer ? (
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
      ) : (
        <AiAnswerBlock task={task} answer={answer} />
      )}

      <div className="mt-hestia-3 border-t border-hestia-border pt-hestia-3">
        <span className="hestia-eyebrow text-hestia-text-muted">Score</span>
        <div className="mt-1 flex flex-wrap items-center gap-hestia-3">
          {task.points != null && (
            <div className="flex min-w-[160px] flex-1 flex-col">
              {/*
                h-9 on the slider stretches its hit area to the full row height:
                the bare Radix Root is only as tall as the 20px thumb, leaving
                dead zones above/below the track where clicks did nothing. With
                a full-height, cursor-pointer Root, a click anywhere in the band
                jumps the thumb.
              */}
              <Slider
                className="h-9 w-full cursor-pointer"
                min={0}
                max={sliderMax}
                step={SCORE_STEP}
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
              {showTicks && (
                <div className="relative mt-0.5 h-1.5" aria-hidden>
                  {Array.from({ length: tickCount }).map((_, i) => (
                    <span
                      key={i}
                      className="absolute top-0 h-1.5 w-px -translate-x-1/2 bg-hestia-border"
                      style={{ left: `${(i / (tickCount - 1)) * 100}%` }}
                    />
                  ))}
                </div>
              )}
            </div>
          )}
          <div className="flex shrink-0 items-center gap-1.5">
            <Input
              type="number"
              data-score-input
              min={0}
              max={task.points ?? undefined}
              step={SCORE_STEP}
              value={scoreStr}
              onWheel={preventNumberWheelChange}
              onChange={(e) => setScoreStr(e.target.value)}
              onBlur={onScoreBlur}
              className={cn(
                "h-9 w-16 bg-hestia-surface text-sm tabular-nums",
                source === "pending"
                  ? "border-hestia-danger"
                  : "border-hestia-border",
              )}
            />
            {maxEdit.editing ? (
              <span className="flex items-center gap-0.5 text-sm text-hestia-text-muted">
                /
                <Input
                  type="number"
                  min={0}
                  step={SCORE_STEP}
                  onWheel={preventNumberWheelChange}
                  {...maxEdit.inputProps}
                  className="no-spinner h-7 w-14 bg-hestia-surface text-sm tabular-nums"
                />
              </span>
            ) : (
              <button
                type="button"
                onClick={maxEdit.startEditing}
                aria-label="Edit max score"
                title="Edit max score"
                className="group flex items-center gap-1 text-sm text-hestia-text-muted transition-colors hover:text-hestia-grading"
              >
                <span className="tabular-nums">
                  {task.points != null ? `/ ${task.points}` : "Set max"}
                </span>
                <Pencil
                  size={12}
                  className="shrink-0 text-hestia-text-muted transition-colors group-hover:text-hestia-grading"
                  aria-hidden
                />
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );

  return <AnswerCard pending={source === "pending"}>{body}</AnswerCard>;
};
