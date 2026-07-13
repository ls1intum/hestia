import {
  type CSSProperties,
  type HTMLAttributes,
  type ReactNode,
} from "react";
import { GripVertical } from "lucide-react";
import { Progress } from "@/components/ui/progress";
import { Separator } from "@/components/ui/separator";
import { cn } from "@/lib/utils";

export type SectionStatus = "draft" | "ready" | "confirmed";

interface Props {
  status?: SectionStatus;
  /** Title node — editable input in Edit, static heading in Grading. */
  title: ReactNode;
  /** Top progress: tasks-with-points (Edit) or tasks-graded (Grading). Omit to hide. */
  progress?: { done: number; total: number };
  /** Optional trailing slot in the top region (e.g. chip ConfirmSectionButton). */
  headerAction?: ReactNode;
  /** Block list rendered flat on the tab background. */
  children: ReactNode;
  /** Optional content rendered above the bottom separator (e.g. AddTaskInline). */
  beforeFooter?: ReactNode;
  /** Optional bottom-left score readout (e.g. total points / graded sum). */
  footerScore?: ReactNode;
  /** Optional bottom-right primary action (e.g. Confirm CTA). */
  footerAction?: ReactNode;
  dragHandleProps?: HTMLAttributes<HTMLButtonElement>;
  setNodeRef?: (el: HTMLElement | null) => void;
  style?: CSSProperties;
  isDragging?: boolean;
}

const statusDotClass = (status: SectionStatus): string => {
  switch (status) {
    case "confirmed":
      return "bg-hestia-success";
    case "ready":
      return "bg-hestia-primary";
    case "draft":
    default:
      return "bg-hestia-border";
  }
};

/**
 * Flat layout for a single section in the carousel. Replaces the old
 * SectionCard. Renders directly on the tab background (no border / surface),
 * with two horizontal separators framing the top header region, the block
 * list, and the bottom score + CTA row.
 */
export const SectionLayout = ({
  status = "draft",
  title,
  progress,
  headerAction,
  children,
  beforeFooter,
  footerScore,
  footerAction,
  dragHandleProps,
  setNodeRef,
  style,
  isDragging,
}: Props) => {
  const pct =
    progress && progress.total > 0
      ? Math.round((progress.done / progress.total) * 100)
      : 0;

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(isDragging && "opacity-60")}
    >
      {/* TOP REGION */}
      <div className="flex items-center gap-hestia-3 py-hestia-3">
        {dragHandleProps && (
          <button
            type="button"
            aria-label="Drag to reorder"
            title="Drag to reorder"
            className="shrink-0 cursor-grab rounded-hestia-sm p-1 text-hestia-text-muted hover:bg-hestia-primary-muted/40 hover:text-hestia-text active:cursor-grabbing touch-none"
            {...dragHandleProps}
          >
            <GripVertical size={14} />
          </button>
        )}
        <span
          aria-hidden
          className={cn(
            "h-2 w-2 shrink-0 rounded-full",
            statusDotClass(status),
          )}
        />
        <div className="min-w-0 flex-1">{title}</div>
        {progress && (
          <div className="hidden items-center gap-hestia-2 sm:flex">
            <Progress value={pct} className="h-1.5 w-32" />
            <span className="hestia-eyebrow tabular-nums text-hestia-text-muted">
              {progress.done}/{progress.total}
            </span>
          </div>
        )}
        {headerAction && (
          <div className="flex shrink-0 items-center gap-hestia-2">
            {headerAction}
          </div>
        )}
      </div>
      <Separator />

      {/* MIDDLE: flat block list */}
      <div className="space-y-hestia-3 py-hestia-3">
        {children}
      </div>

      {/* BOTTOM REGION */}
      {beforeFooter && <div className="pb-hestia-5">{beforeFooter}</div>}
      {(footerScore || footerAction) && (
        <>
          <Separator />
          <div className="flex items-center justify-between gap-hestia-3 py-hestia-5">
            {footerScore && (
              <div className="font-body text-base font-semibold tabular-nums text-hestia-text">
                {footerScore}
              </div>
            )}
            {footerAction && <div className="shrink-0">{footerAction}</div>}
          </div>
        </>
      )}
    </div>
  );
};
