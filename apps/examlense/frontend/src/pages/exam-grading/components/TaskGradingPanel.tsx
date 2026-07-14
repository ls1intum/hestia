import { useEffect, useRef, useState } from "react";
import { Loader2, Sparkles, AlertTriangle, Bot, Check } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Badge } from "@/components/ui/badge";
import { Slider } from "@/components/ui/slider";
import { useQueryClient } from "@tanstack/react-query";
import { useUpsertTaskGrade } from "@/hooks/data/use-task-grades";
import { taskAnswersKey } from "@/hooks/data/use-task-answers";
import { autoGradeChoiceTask, type TaskAnswer, type TaskGrade } from "@/lib/grading/grading";
import { MarkdownView } from "@/components/shared/exam-content/MarkdownView";
import { BlockCard } from "@/components/shared/exam-content/BlockCard";
import type { Task } from "@/lib/exam/exam-helpers";
import { cn } from "@/lib/utils/utils";
import { solveTask } from "@/lib/api/api-solve";
import { GRADE_SOURCE_LABELS } from "@/lib/exam/labels";

const preventNumberWheelChange = (event: React.WheelEvent<HTMLInputElement>) => {
  event.currentTarget.blur();
};

/** Grading increment shared by the slider and the number input. */
const SCORE_STEP = 0.5;
/** Only draw slider tick marks when the count stays readable. */
const MAX_TICKS = 16;

interface Props {
  task: Task;
  examId: string;
  answer: TaskAnswer | undefined;
  grade: TaskGrade | undefined;
  /** Task letter, e.g. "a" — shown in the card header next to the status dot. */
  label: string;
  /** True when this task has a grade (auto or manual). Drives the leading dot. */
  graded?: boolean;
}

/**
 * The gradable "work object" in grading mode: the AI answer (or the selected
 * options for choice tasks) plus the score controls, rendered as a real
 * `primary` card. The static question lives card-less in `ReadOnlyQuestionBlock`
 * above it — so this card, not the question, is the thing the grader acts on.
 */
export const TaskGradingPanel = ({ task, examId, answer, grade, label, graded }: Props) => {
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

  // Tick marks under the slider at each grading step — only when readable.
  const tickCount = sliderMax > 0 ? Math.round(sliderMax / SCORE_STEP) + 1 : 0;
  const showTicks = tickCount > 1 && tickCount <= MAX_TICKS + 1;

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

  const correctIds = new Set(
    (task.options ?? []).filter((o) => o.is_correct).map((o) => o.id),
  );
  const pickedIds = new Set(answer?.selected_option_ids ?? []);

  const header = (
    <div className="flex items-center justify-between gap-hestia-2">
      <span className="inline-flex min-w-0 items-center gap-hestia-2 font-body text-base font-semibold text-hestia-text">
        <span
          aria-hidden
          className={cn(
            "h-1.5 w-1.5 shrink-0 rounded-full",
            graded ? "bg-hestia-success" : "bg-hestia-border",
          )}
        />
        <span className="min-w-0 truncate tabular-nums">
          {label ? `${label})` : "Answer"}
        </span>
      </span>
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
  );

  const body = (
    <div className={cn("relative", generating && "pointer-events-none")} aria-busy={generating}>
      {generating && (
        <div className="absolute inset-0 z-10 flex items-center justify-center rounded-hestia-md bg-hestia-bg/70 backdrop-blur-sm">
          <Loader2 className="h-5 w-5 animate-spin text-hestia-primary" />
        </div>
      )}

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
      ) : (
        <div className="space-y-hestia-2">
          <div className="flex items-center gap-hestia-2">
            <Bot size={12} className="text-hestia-primary" />
            <span className="hestia-eyebrow text-hestia-text-muted">AI answer</span>
          </div>

          {task.type !== "text" ? (
            <ul className="space-y-1.5">
              {(task.options ?? []).map((o) => {
                const isCorrect = correctIds.has(o.id);
                const isPicked = pickedIds.has(o.id);
                return (
                  <li
                    key={o.id}
                    className={cn(
                      "flex items-start gap-hestia-2 rounded-hestia-sm border border-hestia-border/60 px-hestia-2 py-1.5 text-sm",
                      isPicked && "border-hestia-primary/60 bg-hestia-primary-muted/20",
                      isCorrect && "ring-1 ring-hestia-success/40",
                    )}
                  >
                    <span
                      aria-hidden
                      className={cn(
                        "mt-0.5 inline-flex h-4 w-4 shrink-0 items-center justify-center rounded-full border",
                        isPicked
                          ? "border-hestia-primary bg-hestia-primary text-white"
                          : "border-hestia-border text-transparent",
                      )}
                    >
                      {isPicked && <Check size={12} />}
                    </span>
                    <span className="min-w-0 flex-1 break-words text-hestia-text">
                      {o.text || <span className="italic text-hestia-text-muted">—</span>}
                    </span>
                  </li>
                );
              })}
            </ul>
          ) : (
            <>
              {/*
                Rendering is already math-capable: MarkdownView runs remark-math +
                rehype-katex (KaTeX CSS loaded). If formulas show as raw text, the
                solver emitted plain-text notation instead of $…$ LaTeX — that is a
                backend solver-prompt fix, tracked separately, not a rendering bug.
              */}
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
            </>
          )}
        </div>
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
            {task.points != null && (
              <span className="tabular-nums text-sm text-hestia-text-muted">
                / {task.points}
              </span>
            )}
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <BlockCard
      variant="primary"
      header={header}
      body={body}
      className={cn(
        // Needs grading → two red "snakes" travel around the card border
        // (no persistent red border; the card keeps its normal hairline).
        source === "pending" && "snake-danger-border",
      )}
    />
  );
};
