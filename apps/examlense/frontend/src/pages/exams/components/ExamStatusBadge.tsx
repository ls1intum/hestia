import { CheckCircle2, FileText, Gavel, Loader2, Sparkles } from "lucide-react";
import type { Exam } from "@/lib/exam/exam-helpers";

/**
 * Unified status badge shown on every exam card in the dashboard. Covers the
 * five user-facing statuses: Parsing, Draft, Evaluating, Grading, Finished.
 * The DB still also has `ready` (= editable draft that's ready to send) and
 * `failed` (handled separately on its own card variant); `ready` collapses
 * into "Draft" for display.
 */
export const ExamStatusBadge = ({ status }: { status: Exam["status"] }) => {
  // Map DB status -> visible badge. `ready` and any unknown future state
  // collapse to "Draft" so the dashboard never shows a blank chip.
  const variant = (() => {
    switch (status) {
      case "parsing":
        return {
          label: "Parsing",
          className: "bg-hestia-primary/10 text-hestia-primary",
          Icon: Loader2,
          spin: true,
        };
      case "evaluating":
        return {
          label: "Evaluating",
          className: "bg-hestia-success/10 text-hestia-success",
          Icon: Sparkles,
          spin: false,
        };
      case "grading":
        return {
          label: "Grading",
          className: "bg-hestia-accent/10 text-hestia-accent",
          Icon: Gavel,
          spin: false,
        };
      case "finished":
        return {
          label: "Finished",
          className: "bg-hestia-success/10 text-hestia-success",
          Icon: CheckCircle2,
          spin: false,
        };
      case "draft":
      case "ready":
      default:
        return {
          label: "Draft",
          className: "border border-hestia-border text-hestia-text-muted",
          Icon: FileText,
          spin: false,
        };
    }
  })();

  const { Icon, label, className, spin } = variant;
  const isEvaluating = status === "evaluating";
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1.5 rounded-full px-2.5 py-0.5 font-mono text-[11px] font-medium tracking-[0.04em] ${className} ${isEvaluating ? "animate-pulse" : ""}`}
    >
      <Icon
        size={12}
        className={
          isEvaluating
            ? "animate-[pulse_1.4s_ease-in-out_infinite]"
            : spin
              ? "animate-spin"
              : undefined
        }
      />
      {label}
    </span>
  );
};