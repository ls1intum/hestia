/* eslint-disable react-refresh/only-export-components */
import type { ReactNode } from "react";
import {
  ArrowRight,
  CheckCircle2,
  FileSearch,
  Gauge,
  ImagePlus,
  Layers3,
  ListChecks,
  NotebookPen,
  PenLine,
  Plus,
  SlidersHorizontal,
  Sparkles,
} from "lucide-react";
import { cn } from "@/lib/utils/utils";

export interface GuideStep {
  title: string;
  body: string;
  icon: ReactNode;
  tone: "primary" | "accent" | "warning" | "success";
  diagram: ReactNode;
}

const TONE_CLASS: Record<GuideStep["tone"], string> = {
  primary: "bg-hestia-primary-muted text-hestia-primary",
  accent: "bg-hestia-accent/10 text-hestia-accent",
  warning: "bg-hestia-warning/10 text-hestia-warning",
  success: "bg-hestia-success/10 text-hestia-success",
};

/**
 * Stacked infographic rows for first-open and footer help guides. Several cards
 * render faithful miniature mockups of the actual editor UI (the section
 * sidebar, the task points header, and the footer "next section" pill) so each
 * step visually points at the region of the app it describes.
 */
export const StepGuide = ({
  steps,
  density = "default",
}: {
  steps: GuideStep[];
  density?: "default" | "compact";
}) => (
  <div className={cn(density === "compact" ? "space-y-hestia-2" : "space-y-hestia-3")}>
    {steps.map((step, index) => (
      <article
        key={step.title}
        className={cn(
          "grid rounded-hestia-lg border border-hestia-border bg-hestia-surface shadow-hestia-sm md:grid-cols-[minmax(0,1fr)_minmax(17rem,22rem)] md:items-center",
          density === "compact"
            ? "gap-hestia-3 p-hestia-3"
            : "gap-hestia-4 p-hestia-4",
        )}
      >
        <div className="flex items-start gap-hestia-3">
          <span
            className={cn(
              "inline-flex shrink-0 items-center justify-center rounded-hestia-md",
              density === "compact" ? "h-8 w-8 [&_svg]:h-4 [&_svg]:w-4" : "h-10 w-10",
              TONE_CLASS[step.tone],
            )}
            aria-hidden="true"
          >
            {step.icon}
          </span>
          <div className="min-w-0">
            <p className="hestia-eyebrow text-hestia-text-muted">
              Step {index + 1}
            </p>
            <h3 className="mt-1 font-body text-sm font-semibold leading-snug text-hestia-text">
              {step.title}
            </h3>
            <p className="mt-1 text-xs leading-relaxed text-hestia-text-muted">
              {step.body}
            </p>
          </div>
        </div>
        <div
          className={cn(
            "overflow-hidden rounded-hestia-md border border-hestia-border bg-hestia-bg/50",
            density === "compact"
              ? "min-h-[68px] px-hestia-3 py-hestia-2"
              : "min-h-[92px] px-hestia-4 py-hestia-3",
          )}
        >
          {step.diagram}
        </div>
      </article>
    ))}
  </div>
);

const TrackDiagram = ({
  active = 1,
  labels,
}: {
  active?: number;
  labels: string[];
}) => (
  <div className="space-y-hestia-2">
    <div className="flex items-center gap-1.5">
      {labels.map((label, index) => {
        const done = index <= active;
        return (
          <div key={label} className="flex min-w-0 flex-1 items-center gap-1.5">
            <span
              className={cn(
                "inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[10px] font-semibold",
                done
                  ? "bg-hestia-primary text-white"
                  : "bg-hestia-surface text-hestia-text-muted ring-1 ring-hestia-border",
              )}
            >
              {index + 1}
            </span>
            {index < labels.length - 1 && (
              <span
                className={cn(
                  "h-1 min-w-0 flex-1 rounded-full",
                  index < active ? "bg-hestia-primary" : "bg-hestia-border",
                )}
              />
            )}
          </div>
        );
      })}
    </div>
    <div className="grid grid-cols-4 gap-1 text-[9px] font-medium text-hestia-text-muted">
      {labels.map((label) => (
        <span key={label} className="truncate">
          {label}
        </span>
      ))}
    </div>
  </div>
);

const StackDiagram = ({
  rows,
  highlight = 0,
}: {
  rows: string[];
  highlight?: number;
}) => (
  <div className="space-y-1.5">
    {rows.map((row, index) => (
      <div
        key={row}
        className={cn(
          "flex items-center justify-between gap-hestia-2 rounded-hestia-sm border px-2 py-1.5",
          index === highlight
            ? "border-hestia-primary/50 bg-hestia-primary-muted/25"
            : "border-hestia-border bg-hestia-surface",
        )}
      >
        <span className="h-1.5 w-20 rounded-full bg-hestia-text/15" />
        <span className="hestia-eyebrow text-[9px] text-hestia-text-muted">
          {row}
        </span>
      </div>
    ))}
  </div>
);

const SidebarDiagram = () => (
  <div className="space-y-0.5">
    {["Problem 1", "Problem 2", "Problem 3"].map((label, index) => (
      <div
        key={label}
        className={cn(
          "flex items-center gap-1.5 rounded-l-hestia-md px-2 py-1",
          index === 0
            ? "border border-hestia-border bg-hestia-bg text-hestia-text"
            : "border border-transparent text-hestia-text-muted",
        )}
      >
        <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-hestia-danger" />
        <span className="min-w-0 flex-1 truncate text-[10px] font-medium">
          {label}
        </span>
        <span className="hestia-eyebrow shrink-0 text-[9px] tabular-nums text-hestia-text-muted">
          0 PT
        </span>
      </div>
    ))}
    <div className="flex items-center gap-1.5 px-2 py-1 text-hestia-text-muted">
      <Plus size={11} className="shrink-0" />
      <span className="text-[10px] font-medium">Add section</span>
    </div>
  </div>
);

const MediaDiagram = () => (
  <div className="grid grid-cols-3 gap-hestia-2">
    <div className="col-span-2 flex h-16 items-center justify-center rounded-hestia-md border border-hestia-border bg-hestia-surface text-hestia-primary">
      <ImagePlus size={22} />
    </div>
    <div className="space-y-1.5">
      <span className="block h-4 rounded-hestia-sm bg-hestia-primary-muted" />
      <span className="block h-4 rounded-hestia-sm bg-hestia-accent/15" />
      <span className="block h-4 rounded-hestia-sm bg-hestia-warning/15" />
    </div>
  </div>
);

const ScoringHeaderDiagram = () => (
  <div className="space-y-1.5">
    {[
      { label: "a)", filled: false },
      { label: "b)", filled: true },
    ].map((row) => (
      <div
        key={row.label}
        className="flex items-center gap-1.5 rounded-hestia-sm border border-hestia-border bg-hestia-surface px-2 py-1.5"
      >
        <span className="text-xs font-semibold tabular-nums text-hestia-text">
          {row.label}
        </span>
        <span className="hestia-eyebrow ml-auto text-[9px] text-hestia-text-muted">
          Points
        </span>
        <span
          className={cn(
            "inline-flex h-5 w-9 shrink-0 items-center justify-center rounded-hestia-sm border bg-hestia-surface text-[10px] font-semibold tabular-nums",
            row.filled
              ? "border-hestia-border text-hestia-text"
              : "border-hestia-danger text-transparent",
          )}
        >
          {row.filled ? "4" : "0"}
        </span>
        <span className="shrink-0 whitespace-nowrap rounded-full bg-hestia-primary-muted/40 px-1.5 py-0.5 text-[9px] font-medium text-hestia-text">
          Single Choice
        </span>
      </div>
    ))}
  </div>
);

const NextSectionDiagram = () => (
  <div className="flex items-center justify-center">
    <span className="relative block w-full max-w-[16rem]">
      <span className="relative flex h-7 w-full items-center justify-center gap-1.5 overflow-hidden rounded-hestia-md border border-hestia-success/60 bg-hestia-success/15 text-[10px] font-semibold text-hestia-success">
        <span
          aria-hidden
          className="absolute inset-y-0 left-0 w-full bg-hestia-success/30"
        />
        <span className="relative z-10 inline-flex items-center gap-1.5">
          <ArrowRight size={12} />
          Next section
        </span>
      </span>
    </span>
  </div>
);

const ReviewDiagram = () => (
  <div className="space-y-1.5">
    <div className="flex items-center gap-1.5">
      <span className="h-7 w-7 rounded-hestia-md bg-hestia-primary-muted" />
      <span className="h-2 flex-1 rounded-full bg-hestia-text/15" />
    </div>
    <span className="block h-2 w-full rounded-full bg-hestia-text/10" />
    <span className="block h-2 w-2/3 rounded-full bg-hestia-text/10" />
  </div>
);

const ScoreDiagram = () => (
  <div className="space-y-hestia-2">
    <div className="h-2 rounded-full bg-hestia-border">
      <span className="block h-full w-2/3 rounded-full bg-hestia-primary" />
    </div>
    <div className="flex items-center justify-between">
      <span className="hestia-eyebrow text-hestia-text-muted">Score</span>
      <span className="rounded-hestia-md border border-hestia-border bg-hestia-surface px-2 py-1 text-xs font-semibold tabular-nums text-hestia-text">
        6 / 8
      </span>
    </div>
  </div>
);

const FinishDiagram = () => (
  <div className="flex items-center justify-between gap-hestia-2">
    <div className="flex -space-x-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-hestia-surface bg-hestia-success text-white"
        >
          <CheckCircle2 size={13} />
        </span>
      ))}
    </div>
    <span className="rounded-hestia-md bg-hestia-success px-2 py-1 text-[10px] font-semibold text-white">
      Finish
    </span>
  </div>
);

export const EDIT_STEPS: GuideStep[] = [
  {
    title: "Structure the exam",
    body: "Create sections that match the way the exam should be reviewed.",
    icon: <Layers3 size={18} />,
    tone: "primary",
    diagram: <TrackDiagram labels={["Sections", "Context", "Tasks", "Ready"]} />,
  },
  {
    title: "Add tasks and context",
    body: "Add questions, shared instructions, reference text, and figures.",
    icon: <NotebookPen size={18} />,
    tone: "accent",
    diagram: <StackDiagram rows={["Context", "Task", "Figure"]} highlight={1} />,
  },
  {
    title: "Set scoring",
    body: "Assign points so every task can be solved and graded consistently.",
    icon: <SlidersHorizontal size={18} />,
    tone: "warning",
    diagram: <ScoringHeaderDiagram />,
  },
  {
    title: "Confirm each section",
    body: "Confirm ready sections, then send the whole exam to evaluation.",
    icon: <CheckCircle2 size={18} />,
    tone: "success",
    diagram: <NextSectionDiagram />,
  },
];

export const PARSED_STEPS: GuideStep[] = [
  {
    title: "Review parser output",
    body: "Check sections, task text, answers, options, and any extracted points.",
    icon: <FileSearch size={18} />,
    tone: "primary",
    diagram: <SidebarDiagram />,
  },
  {
    title: "Restore missing material",
    body: "Add omitted graphics, context blocks, or clarifications before solving.",
    icon: <ImagePlus size={18} />,
    tone: "accent",
    diagram: <MediaDiagram />,
  },
  {
    title: "Normalize grading",
    body: "Set or correct point values so the evaluation has a clean rubric.",
    icon: <Gauge size={18} />,
    tone: "warning",
    diagram: <ScoringHeaderDiagram />,
  },
  {
    title: "Confirm sections",
    body: "Mark each reviewed section as ready before starting evaluation.",
    icon: <CheckCircle2 size={18} />,
    tone: "success",
    diagram: <NextSectionDiagram />,
  },
];

export const GRADING_STEPS: GuideStep[] = [
  {
    title: "Review the AI answer",
    body: "Read the generated response beside the original task context.",
    icon: <PenLine size={18} />,
    tone: "primary",
    diagram: <ReviewDiagram />,
  },
  {
    title: "Adjust the score",
    body: "Use the score control to accept or correct the automatic result.",
    icon: <SlidersHorizontal size={18} />,
    tone: "warning",
    diagram: <ScoreDiagram />,
  },
  {
    title: "Complete every section",
    body: "Work through each section until all tasks have final scores.",
    icon: <ListChecks size={18} />,
    tone: "accent",
    diagram: <TrackDiagram active={2} labels={["Review", "Score", "Confirm", "Done"]} />,
  },
  {
    title: "Finish grading",
    body: "Finalize the exam to unlock the results dashboard and insights.",
    icon: <Sparkles size={18} />,
    tone: "success",
    diagram: <FinishDiagram />,
  },
];
