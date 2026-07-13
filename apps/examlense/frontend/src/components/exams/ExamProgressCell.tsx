import type { ExamListItem } from "@/lib/api-client";
import { examProgress } from "@/lib/exam-progress";
import { Progress } from "@/components/ui/progress";
import { cn } from "@/lib/utils";

/**
 * Compact progress indicator for the dashboard table. Renders a small bar + a
 * percentage whose meaning follows the exam's current phase (see
 * {@link examProgress}); shows a dash for failed / task-less exams.
 */
export const ExamProgressCell = ({ exam }: { exam: ExamListItem }) => {
  const { percent, label, state } = examProgress(exam);

  if (percent == null) {
    return (
      <span
        className={cn(
          "text-xs",
          state === "error" ? "text-destructive" : "text-hestia-text-muted",
        )}
        title={label}
      >
        {state === "error" ? "Failed" : "—"}
      </span>
    );
  }

  return (
    <div className="flex w-[120px] max-w-full flex-col gap-1" title={`${label}: ${percent}%`}>
      <Progress value={percent} className="h-1.5 bg-hestia-success/10" />
      <div className="flex items-center justify-between text-[10px] text-hestia-text-muted">
        <span className="truncate">{label}</span>
        <span className="tabular-nums">{percent}%</span>
      </div>
    </div>
  );
};
