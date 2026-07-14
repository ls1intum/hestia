import { Link } from "react-router-dom";
import { ArrowLeft, ArrowRight, CheckCircle2, Loader2 } from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { Button } from "@/components/ui/button";
import { ParsingQualitySurvey } from "@/pages/exam-edit/components/ParsingQualitySurvey";
import {
  useEvaluationProgress,
  parsePhasePercent,
} from "@/hooks/data/use-exam-progress";

type ProcessingKind = "parsing" | "evaluating";

const PARSE_PHASE_LABELS: Record<string, string> = {
  queued: "Queued…",
  downloading: "Downloading PDF…",
  rasterizing: "Rendering PDF pages…",
  extracting: "Identifying tasks with AI…",
  persisting: "Saving parsed tasks…",
};

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
  solveDone = false,
  onContinue,
  onCancel,
}: {
  title: string;
  kind?: ProcessingKind;
  examId?: string;
  parsePhase?: string | null;
  solveDone?: boolean;
  onContinue?: () => void;
  onCancel?: () => void;
}) => {
  const isEvaluating = kind === "evaluating";
  const heading =
    kind === "parsing"
      ? "Parsing your exam…"
      : solveDone
        ? "Your exam is ready"
        : "Evaluating your exam…";
  const bodyText =
    kind === "parsing"
      ? "We're extracting sections and tasks from your PDF. You can leave this page and come back at any time — we'll keep working in the background."
      : solveDone
        ? "We've finished solving every task. Continue to grading mode to review the results."
        : "This may take a few minutes. You can leave this page and come back at any time — we'll keep working in the background.";

  const { done, total, percent: evalPercent } = useEvaluationProgress(
    isEvaluating ? examId : undefined,
  );

  const isParsing = kind === "parsing";
  const percent = isParsing
    ? parsePhasePercent(parsePhase)
    : solveDone
      ? 100
      : evalPercent;
  const phaseLabel = isParsing
    ? PARSE_PHASE_LABELS[parsePhase ?? "queued"] ?? PARSE_PHASE_LABELS.queued
    : total > 0
      ? `Solving task ${done} of ${total}…`
      : "Preparing tasks…";

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
          <span className="mb-hestia-5 inline-flex h-14 w-14 items-center justify-center rounded-full bg-hestia-success/10 text-hestia-success">
            {solveDone ? (
              <CheckCircle2 size={28} />
            ) : (
              <Loader2 size={28} className="animate-spin" />
            )}
          </span>
          <p className="hestia-eyebrow text-hestia-text-muted">
            {title}
          </p>
          <h1 className="mt-hestia-2 font-display text-2xl font-semibold text-hestia-text">
            {heading}
          </h1>
          <p className="mt-hestia-3 text-sm text-hestia-text-muted">
            {bodyText}
          </p>
          <div className="mt-hestia-5 w-full">
            <Progress
              value={percent}
              className="h-2 bg-hestia-success/10"
            />
            <div className="mt-hestia-2 flex items-center justify-between text-xs text-hestia-text-muted">
              <span>{phaseLabel}</span>
              <span className="tabular-nums">{percent}%</span>
            </div>
          </div>
          {solveDone && onContinue && (
            <Button className="mt-hestia-5 gap-1" onClick={onContinue}>
              Continue to grading mode
              <ArrowRight size={16} />
            </Button>
          )}
          {!solveDone && onCancel && (
            <Button
              variant="ghost"
              size="sm"
              className="mt-hestia-5 text-hestia-text-muted hover:text-hestia-danger"
              onClick={onCancel}
            >
              Cancel
            </Button>
          )}
          {isEvaluating && examId && (
            <ParsingQualitySurvey examId={examId} />
          )}
        </div>
      </main>
    </div>
  );
};