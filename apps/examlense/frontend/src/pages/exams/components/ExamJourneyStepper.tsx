import {
  ArrowRight,
  BookCheck,
  Check,
  HelpCircle,
  ListChecks,
  Pencil,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { ExamJourney, ExamStep } from "@/lib/exam/exam-progress";
import { ProgressRing } from "@/components/shared/ProgressRing";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils/utils";

/** One ring of the journey — muted when pending, colored+filled when active,
 *  and a filled ring with `DoneIcon` (a Check by default) once done. */
const StepRing = ({
  step,
  activeClassName,
  Icon,
  DoneIcon = Check,
}: {
  step: ExamStep;
  activeClassName: string;
  Icon: LucideIcon;
  DoneIcon?: LucideIcon;
}) => {
  const done = step.state === "done";
  const pending = step.state === "pending";
  // NB: never use an `/opacity` modifier on hestia-border / hestia-text-muted —
  // those tokens bake in an alpha channel, so `.../60` yields invalid CSS and
  // the stroke/text silently falls back to the inherited (wrong) color.
  const iconColor = done
    ? "text-hestia-success"
    : pending
      ? "text-hestia-text-muted"
      : activeClassName;
  return (
    <ProgressRing
      value={done ? 100 : step.value}
      size={30}
      strokeWidth={3.5}
      // Pending (not-yet-reached) steps read as clearly inactive.
      className={pending ? "opacity-50" : undefined}
      trackClassName={pending ? "text-hestia-border" : "text-hestia-border-strong"}
      indicatorClassName={done ? "text-hestia-success" : activeClassName}
    >
      {done ? (
        <DoneIcon className={iconColor} size={13} strokeWidth={2.75} />
      ) : (
        <Icon className={iconColor} size={12} strokeWidth={2.25} />
      )}
    </ProgressRing>
  );
};

/** A ring plus its optional `done/total` count. The count is absolutely
 *  positioned beneath the ring so it never shifts the ring off-center or changes
 *  the row height — only the ring participates in the row's vertical centering.
 *  Shown only for the active step. */
const StepColumn = ({
  step,
  activeClassName,
  Icon,
  DoneIcon,
}: {
  step: ExamStep;
  activeClassName: string;
  Icon: LucideIcon;
  DoneIcon?: LucideIcon;
}) => (
  <div className="relative flex items-center">
    <StepRing step={step} activeClassName={activeClassName} Icon={Icon} DoneIcon={DoneIcon} />
    {step.state === "active" && (
      <span
        className={cn(
          "absolute left-1/2 top-full mt-1 -translate-x-1/2 text-[10px] leading-none tabular-nums",
          activeClassName,
        )}
      >
        {/* Count completed, not remaining: `remaining` is tasks still to do, so a
            fully-scored exam must read "18/18" (and count up as you progress). */}
        {step.total - step.remaining}/{step.total}
      </span>
    )}
  </div>
);

/** `active` = AI solving (pulses); `ready` = scoring done, solving can start
 *  next (solid, un-muted); otherwise muted. */
const Connector = ({ active, ready }: { active?: boolean; ready?: boolean }) => (
  <ArrowRight
    size={14}
    className={cn(
      "shrink-0",
      active
        ? "animate-pulse text-hestia-accent"
        : ready
          ? "text-hestia-primary"
          : "text-hestia-text-muted opacity-50",
    )}
  />
);

/**
 * Three-step Score → Grade → Finish indicator for the dashboard Progress column.
 * The first two rings track the user's jobs (set max scores, then grade AI
 * answers); the third is the "goal", filled only once the exam is finished. The
 * connector arrow animates while the AI is solving in between. Each active ring's
 * `done/total` count sits beneath it; the full sentence lives in the tooltip.
 */
export const ExamJourneyStepper = ({ journey }: { journey: ExamJourney }) => {
  const { score, grade, finish, aiSolving, detail } = journey;
  const SCORE_COLOR = "text-hestia-primary";
  const GRADE_COLOR = "text-hestia-accent";
  // Scoring complete but not yet solving (draft/ready, all tasks scored): the
  // score ring is active-and-full. Un-mute the score→grade arrow to signal the
  // exam is ready to move on to LLM solving.
  const readyToSolve = score.state === "active" && score.value >= 100;

  return (
    // Styled tooltip (not a native `title`, which was slow and only triggered on
    // the small rings). Short delay + a full-width trigger so hovering anywhere in
    // the Progress cell reveals the "X to score / ready to solve" detail.
    <Tooltip delayDuration={150}>
      <TooltipTrigger asChild>
        <div
          // min-h matches the parsing bar so rows stay a uniform height; w-full
          // makes the whole cell the hover target; items-center centers the rings
          // vertically (labels hang below, out of flow).
          className="flex min-h-[50px] w-full cursor-default items-center gap-2"
          aria-label={detail}
        >
          <StepColumn step={score} activeClassName={SCORE_COLOR} Icon={Pencil} />
          <Connector active={aiSolving} ready={readyToSolve} />
          <StepColumn step={grade} activeClassName={GRADE_COLOR} Icon={ListChecks} />
          <Connector />
          <StepColumn
            step={finish}
            activeClassName="text-hestia-success"
            Icon={BookCheck}
            DoneIcon={BookCheck}
          />
          {/* Hover affordance: a subtle "?" hints that the row explains the next
              step. The whole row is the tooltip trigger, so hovering it works too. */}
          <HelpCircle
            size={13}
            className="ml-1 shrink-0 text-hestia-text-muted"
            aria-hidden="true"
          />
        </div>
      </TooltipTrigger>
      <TooltipContent>{detail}</TooltipContent>
    </Tooltip>
  );
};
