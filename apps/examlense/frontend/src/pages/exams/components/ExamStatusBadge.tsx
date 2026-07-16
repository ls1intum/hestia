import type { Exam } from "@/lib/exam/exam-helpers";
import { examStatusMeta } from "@/lib/exam/exam-status";

/**
 * Unified status badge shown on every exam row in the dashboard. Appearance and
 * labels come from the shared `EXAM_STATUS_META` table (`lib/exam/exam-status`),
 * where `ready` and `failed` collapse into the neutral "Draft" chip.
 */
export const ExamStatusBadge = ({ status }: { status: Exam["status"] }) => {
  const { Icon, label, className, spin } = examStatusMeta(status);
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
