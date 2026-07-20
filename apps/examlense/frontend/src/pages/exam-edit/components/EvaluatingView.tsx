import { Link } from "react-router-dom";
import {
  AlertTriangle,
  ArrowLeft,
  ArrowRight,
  CheckCircle2,
  RotateCcw,
} from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils/utils";
import { ForgeAnimation } from "@/pages/exam-edit/components/ForgeAnimation";
import { ParsingQualitySurvey } from "@/pages/exam-edit/components/ParsingQualitySurvey";
import {
  useEvaluationProgress,
  useParseCountdown,
  parsePhasePercent,
  parsePhaseLabel,
} from "@/hooks/data/use-exam-progress";

type ProcessingKind = "parsing" | "evaluating";

/**
 * Full-page loading state shown in the editor while an exam is being
 * processed in the background (parsing the uploaded PDF or evaluating the
 * confirmed exam). The user can leave at any time — work continues
 * server-side and the page will update via realtime when the status flips.
 */
export const EvaluatingView = ({
  title,
  kind = "evaluating",
  examId,
  parsePhase,
  pageCount,
  parseStartedAt,
  solveDone = false,
  variant = "loading",
  errorMessage,
  errorHeading = "Parsing failed",
  retryLabel = "Retry parsing",
  onContinue,
  onCancel,
  onRetry,
}: {
  title: string;
  kind?: ProcessingKind;
  examId?: string;
  parsePhase?: string | null;
  pageCount?: number | null;
  parseStartedAt?: string | null;
  solveDone?: boolean;
  variant?: "loading" | "error";
  errorMessage?: string | null;
  /** Error-state heading (defaults to the parse-failure copy). */
  errorHeading?: string;
  /** Error-state retry button label (defaults to the parse-failure copy). */
  retryLabel?: string;
  onContinue?: () => void;
  onCancel?: () => void;
  onRetry?: () => void;
}) => {
  const isError = variant === "error";
  const isEvaluating = kind === "evaluating";
  const heading = isError
    ? errorHeading
    : kind === "parsing"
      ? "Parsing your exam…"
      : solveDone
        ? "Your exam is ready"
        : "Evaluating your exam…";
  const bodyText = isError
    ? errorMessage ||
      "We couldn't read this PDF. Please retry or upload a different file."
    : kind === "parsing"
      ? "We're extracting sections and tasks from your PDF. You can leave this page and come back at any time — we'll keep working in the background."
      : solveDone
        ? "We've finished solving every task. Continue to grading mode to review the results."
        : "This may take a few minutes. You can leave this page and come back at any time — we'll keep working in the background.";

  const { done, total, percent: evalPercent } = useEvaluationProgress(
    isEvaluating ? examId : undefined,
  );

  const isParsing = kind === "parsing";
  // Live page-count-based parsing estimate (null → fall back to phase percent).
  const countdown = useParseCountdown({
    active: isParsing && !isError,
    pageCount,
    parseStartedAt,
    parsePhase,
  });

  const percent = isParsing
    ? countdown
      ? countdown.percent
      : parsePhasePercent(parsePhase)
    : solveDone
      ? 100
      : evalPercent;
  const phaseLabel = isParsing
    ? parsePhaseLabel(parsePhase)
    : total > 0
      ? `Solving task ${done} of ${total}…`
      : "Preparing tasks…";
  // For parsing, show remaining time when we have an estimate; otherwise the raw
  // percent. Evaluating always shows its work-derived percent.
  const rightLabel =
    isParsing && countdown ? countdown.remainingLabel : `${percent}%`;

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-hestia-border bg-hestia-surface/60 px-hestia-5 py-hestia-3">
        <Link
          to="/exams"
          className="inline-flex items-center gap-1 text-sm text-hestia-text-muted hover:text-hestia-primary"
        >
          <ArrowLeft size={14} />
          Back to exams
        </Link>
      </header>
      <main className="flex flex-1 items-center justify-center px-hestia-5 py-hestia-10">
        <div className="flex w-full max-w-md flex-col items-center text-center">
          {isError || solveDone ? (
            <span
              className={cn(
                "mb-hestia-5 inline-flex h-14 w-14 items-center justify-center rounded-full",
                isError
                  ? "bg-hestia-danger/10 text-hestia-danger"
                  : "bg-hestia-success/10 text-hestia-success",
              )}
            >
              {isError ? <AlertTriangle size={28} /> : <CheckCircle2 size={28} />}
            </span>
          ) : (
            <ForgeAnimation className="mb-hestia-4" />
          )}
          <p className="hestia-eyebrow text-hestia-text-muted">
            {title}
          </p>
          <h1 className="mt-hestia-2 font-display text-2xl font-semibold text-hestia-text">
            {heading}
          </h1>
          <p className="mt-hestia-3 text-sm text-hestia-text-muted">
            {bodyText}
          </p>
          {!isError && (
            <div className="mt-hestia-5 w-full">
              <Progress
                value={percent}
                className="h-2 bg-hestia-success/10"
              />
              <div className="mt-hestia-2 flex items-center justify-between text-xs text-hestia-text-muted">
                <span>{phaseLabel}</span>
                <span className="tabular-nums">{rightLabel}</span>
              </div>
            </div>
          )}
          {isError && onRetry && (
            <Button className="mt-hestia-5 gap-1" onClick={onRetry}>
              <RotateCcw size={16} />
              {retryLabel}
            </Button>
          )}
          {!isError && solveDone && onContinue && (
            <Button className="mt-hestia-5 gap-1" onClick={onContinue}>
              Continue to grading mode
              <ArrowRight size={16} />
            </Button>
          )}
          {!isError && !solveDone && onCancel && (
            <Button
              variant="ghost"
              size="sm"
              className="mt-hestia-5 text-hestia-text-muted hover:text-hestia-danger"
              onClick={onCancel}
            >
              Cancel
            </Button>
          )}
          {!isError && isEvaluating && examId && (
            <ParsingQualitySurvey examId={examId} />
          )}
        </div>
      </main>
    </div>
  );
};