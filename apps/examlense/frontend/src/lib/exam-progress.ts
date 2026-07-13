import type { ExamListItem } from "@/lib/api-client";
import { parsePhasePercent, parsePhaseLabel } from "@/hooks/use-exam-progress";

/**
 * Unified "current-phase" progress for an exam row in the dashboard table.
 *
 * The meaning of the percentage depends on where the exam is in its lifecycle:
 * - parsing    → parse-phase progress (from `parse_phase`)
 * - evaluating → solved tasks (answered / total)
 * - draft/ready→ tasks with a max score set (scored / total)
 * - grading    → tasks graded (graded / total)
 * - finished   → 100%
 * - failed     → error state (no bar)
 *
 * `percent` is null when there's nothing meaningful to show (e.g. failed, or an
 * exam with no tasks yet).
 */
export interface ExamProgress {
  percent: number | null;
  label: string;
  state: "normal" | "error";
}

const pct = (num: number, denom: number): number | null =>
  denom > 0 ? Math.round((num / denom) * 100) : null;

export const examProgress = (exam: ExamListItem): ExamProgress => {
  const total = exam.task_count;
  switch (exam.status) {
    case "parsing":
      return {
        percent: parsePhasePercent(exam.parse_phase),
        label: parsePhaseLabel(exam.parse_phase),
        state: "normal",
      };
    case "failed":
      return { percent: null, label: "Failed", state: "error" };
    case "evaluating": {
      const p = pct(exam.answered_count, total);
      return { percent: p, label: p == null ? "Preparing…" : "Solving", state: "normal" };
    }
    case "grading": {
      const p = pct(exam.graded_count, total);
      return { percent: p, label: p == null ? "No tasks" : "Graded", state: "normal" };
    }
    case "finished":
      return { percent: 100, label: "Complete", state: "normal" };
    case "draft":
    case "ready":
    default: {
      const p = pct(exam.scored_count, total);
      return { percent: p, label: p == null ? "No tasks" : "Scored", state: "normal" };
    }
  }
};

/**
 * Comparable value for sorting the Progress column. Failed/absent progress sorts
 * lowest (-1) so completed/high-progress exams surface first on a descending sort.
 */
export const progressSortValue = (exam: ExamListItem): number =>
  examProgress(exam).percent ?? -1;
