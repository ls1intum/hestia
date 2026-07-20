import { useEffect, useState } from "react";
import { HelpCircle, Info } from "lucide-react";
import type { ExamListItem } from "@/lib/api/api-client";
import { examJourney } from "@/lib/exam/exam-progress";
import {
  parsePhaseLabel,
  parsePhasePercent,
  useParseCountdown,
} from "@/hooks/data/use-exam-progress";
import { Badge } from "@/components/ui/badge";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ExamJourneyStepper } from "./ExamJourneyStepper";
import { ParsingProgress } from "./ParsingProgress";

/**
 * Compact progress indicator for the dashboard table. For an in-flight exam it
 * renders a two-step Score → Grade journey (see {@link examJourney} /
 * {@link ExamJourneyStepper}). Parsing keeps its live time-based countdown bar,
 * a failed exam shows a "Failed" badge with an info icon that reveals the
 * detailed, actionable parse error, and a task-less exam shows a dash.
 */
export const ExamProgressCell = ({ exam }: { exam: ExamListItem }) => {
  const journey = examJourney(exam);
  // Live, page-count-based estimate while parsing (null → fall back to the
  // phase-based bar below). Hook called unconditionally per the rules of hooks.
  const countdown = useParseCountdown({
    active: exam.status === "parsing",
    pageCount: exam.page_count,
    parseStartedAt: exam.parse_started_at,
    parsePhase: exam.parse_phase,
  });

  // When parsing finishes, briefly fill the bar to 100% before switching to the
  // draft stepper (so an early finish doesn't snap away mid-fill). Detected with
  // the adjust-state-during-render pattern to avoid a post-paint flash.
  const [prevStatus, setPrevStatus] = useState(exam.status);
  const [completing, setCompleting] = useState(false);
  if (exam.status !== prevStatus) {
    if (prevStatus === "parsing" && exam.status !== "failed") setCompleting(true);
    setPrevStatus(exam.status);
  }
  useEffect(() => {
    if (!completing) return;
    const t = setTimeout(() => setCompleting(false), 800);
    return () => clearTimeout(t);
  }, [completing]);

  if (completing) {
    return <ParsingProgress label="Complete" percent={100} />;
  }

  if (journey.kind === "error") {
    return (
      // Stop propagation so opening the detail never triggers row navigation.
      <div
        className="flex items-center gap-1.5"
        onClick={(e) => e.stopPropagation()}
      >
        <Badge className="border-hestia-danger/20 bg-hestia-danger/10 text-hestia-danger hover:bg-hestia-danger/10">
          Failed
        </Badge>
        <Popover>
          <PopoverTrigger asChild>
            <button
              type="button"
              aria-label="Why it failed"
              className="inline-flex h-5 w-5 items-center justify-center rounded-full text-hestia-text-muted transition-colors hover:text-destructive focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-hestia-primary"
            >
              <Info size={14} />
            </button>
          </PopoverTrigger>
          <PopoverContent
            align="start"
            className="w-72 text-sm leading-relaxed text-hestia-text"
          >
            {exam.parse_error ||
              "Something went wrong. Open the exam to retry."}
          </PopoverContent>
        </Popover>
      </div>
    );
  }

  if (journey.kind === "empty") {
    return (
      <span className="text-xs text-hestia-text-muted" title={journey.detail}>
        —
      </span>
    );
  }

  // Incomplete: tasks exist but no section holds them. Not on the normal journey
  // (and not "ready to solve"), so it gets its own surface: a "?" with the label
  // and a tooltip pointing the user to open the exam and add a section.
  if (journey.kind === "incomplete") {
    return (
      <Tooltip delayDuration={150}>
        <TooltipTrigger asChild>
          <span className="flex min-h-[50px] w-full cursor-default items-center gap-1.5 text-xs text-hestia-text-muted">
            <HelpCircle size={14} className="shrink-0" aria-hidden="true" />
            {journey.primary}
          </span>
        </TooltipTrigger>
        <TooltipContent>{journey.detail}</TooltipContent>
      </Tooltip>
    );
  }

  // Parsing: live time-driven bar when a page-count estimate exists (with a
  // remaining-time hint), otherwise a coarse phase-based bar.
  if (journey.kind === "parsing") {
    return (
      <ParsingProgress
        label={parsePhaseLabel(exam.parse_phase)}
        percent={countdown ? countdown.percent : parsePhasePercent(exam.parse_phase)}
        hint={countdown?.remainingLabel}
      />
    );
  }

  // Evaluating: a live "Solving task X of Y…" bar (mirrors the /edit splash),
  // driven by answered_count which now refetches per section batch via SSE.
  if (journey.kind === "evaluating") {
    const total = exam.task_count;
    const percent = total > 0 ? Math.round((exam.answered_count / total) * 100) : 0;
    return (
      <ParsingProgress
        label={journey.primary}
        percent={percent}
        barClassName="bg-hestia-grading/10 [&>div]:bg-hestia-grading"
      />
    );
  }

  return <ExamJourneyStepper journey={journey} />;
};
