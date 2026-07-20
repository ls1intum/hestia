import type { ExamListItem } from "@/lib/api/api-client";
import { isParseFailure } from "@/lib/exam/exam-helpers";
import { parsePhasePercent, parsePhaseLabel } from "@/hooks/data/use-exam-progress";

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

/**
 * The two-step "journey" view of an exam for the dashboard Progress column: a
 * Score ring → a Grade ring. The two rings track the user's two responsibilities
 * (assign max points, then grade the AI's answers); the transient AI-solve
 * (evaluating) phase rides on the connector between them (`aiSolving`).
 *
 * `kind` tells the cell how to render: `parsing`/`error`/`empty` keep their
 * existing special surfaces (live countdown bar, Failed badge, dash); only
 * `steps` renders the two rings.
 */
export type StepState = "pending" | "active" | "done";

export interface ExamStep {
  state: StepState;
  /** Ring fill percentage, 0–100. */
  value: number;
  /** Tasks still to do for this step (drives the `remaining/total` label). */
  remaining: number;
  /** Total tasks for this step's denominator. */
  total: number;
}

export interface ExamJourney {
  kind: "parsing" | "error" | "empty" | "steps" | "evaluating" | "incomplete";
  score: ExamStep;
  grade: ExamStep;
  /** The "goal" step — `done` only once the exam is finished, else `pending`. */
  finish: ExamStep;
  /** True while the AI is solving (evaluating) — animate the connector. */
  aiSolving: boolean;
  /** Compact in-cell label (e.g. "2 to score"). */
  primary: string;
  /** Full sentence for the tooltip (e.g. "2 answers need grading"). */
  detail: string;
}

/** "task" / "tasks" — pluralise the remaining-count noun in the tooltip copy. */
const plural = (n: number, noun: string): string => (n === 1 ? noun : `${noun}s`);

export const examJourney = (exam: ExamListItem): ExamJourney => {
  const total = exam.task_count;

  if (exam.status === "failed") {
    const detail = isParseFailure(exam) ? "Parsing failed" : "Evaluation failed";
    return { kind: "error", score: PENDING, grade: PENDING, finish: PENDING, aiSolving: false, primary: "Failed", detail };
  }
  if (exam.status === "parsing") {
    return { kind: "parsing", score: PENDING, grade: PENDING, finish: PENDING, aiSolving: false, primary: "Parsing", detail: "Reading the PDF" };
  }
  if (total <= 0) {
    // Sections exist but hold no tasks (e.g. every task was deleted) — a broken,
    // incomplete state, not a brand-new blank exam. Surface it like the
    // no-sections case (the "?" indicator) instead of a bare dash.
    if (exam.section_count > 0) {
      return {
        kind: "incomplete",
        score: PENDING,
        grade: PENDING,
        finish: PENDING,
        aiSolving: false,
        primary: "No tasks present",
        detail: "This exam has sections but no tasks. Open it and add tasks to start solving.",
      };
    }
    return { kind: "empty", score: PENDING, grade: PENDING, finish: PENDING, aiSolving: false, primary: "No tasks", detail: "No tasks yet" };
  }

  // Tasks exist but no section holds them (e.g. the last section was deleted,
  // orphaning its tasks). Scoped to the pre-solve editable states: the section
  // count is the gate to solving, so zero sections is incomplete — NOT "ready to
  // solve" (this matches the editor's `allSectionsReady`, which is false with no
  // real sections). Later states require confirmed sections, so can't hit this.
  if ((exam.status === "draft" || exam.status === "ready") && exam.section_count === 0) {
    return {
      kind: "incomplete",
      score: PENDING,
      grade: PENDING,
      finish: PENDING,
      aiSolving: false,
      primary: "No sections found",
      detail: "This exam has tasks but no sections. Open it and add a section to start solving.",
    };
  }

  // The first "prepare" step tracks section confirmation (the real gate to
  // solving), not task scoring: confirming the sections is what starts the run.
  const sectionTotal = exam.section_count;
  const confirmed = exam.confirmed_section_count;
  const toConfirm = Math.max(sectionTotal - confirmed, 0);
  const confirmedPct = pct(confirmed, sectionTotal) ?? 0;

  const gradedPct = pct(exam.graded_count, total) ?? 0;
  const toGrade = Math.max(total - exam.graded_count, 0);
  const graded = total - toGrade;

  // Tooltip copy: a status lead + an actionable next step tailored to the phase.
  const scoreDetail =
    toConfirm > 0
      ? `${confirmed} of ${sectionTotal} sections confirmed — confirm the remaining ${plural(toConfirm, "section")} to start solving.`
      : "All sections confirmed — start the LLM solving.";
  const gradeDetail =
    toGrade > 0
      ? `${graded} of ${total} graded — grade the answers for the remaining ${plural(toGrade, "task")}.`
      : "All answers graded — ready for the final results.";

  const scoreStep = (state: StepState): ExamStep => ({
    state,
    value: state === "done" ? 100 : confirmedPct,
    remaining: toConfirm,
    total: sectionTotal,
  });
  const gradeStep = (state: StepState): ExamStep => ({
    state,
    value: state === "done" ? 100 : gradedPct,
    remaining: toGrade,
    total,
  });
  // The goal step: filled/done only when the whole exam is finished.
  const finishStep = (state: StepState): ExamStep => ({
    state,
    value: state === "done" ? 100 : 0,
    remaining: 0,
    total,
  });

  switch (exam.status) {
    case "draft":
    case "ready":
      // Scoring in progress (or complete but not yet sent to the AI).
      return {
        kind: "steps",
        score: scoreStep("active"),
        grade: gradeStep("pending"),
        finish: finishStep("pending"),
        aiSolving: false,
        primary: toConfirm > 0 ? `${toConfirm} to confirm` : "Ready to solve",
        detail: scoreDetail,
      };
    case "evaluating":
      // Rendered as a live loading bar (not the stepper) on the dashboard.
      return {
        kind: "evaluating",
        score: scoreStep("done"),
        grade: gradeStep("pending"),
        finish: finishStep("pending"),
        aiSolving: true,
        primary:
          total > 0
            ? `Solving task ${exam.answered_count} of ${total}…`
            : "Preparing tasks…",
        detail: `AI is solving — ${exam.answered_count} of ${total} answered`,
      };
    case "grading":
      return {
        kind: "steps",
        score: scoreStep("done"),
        // A check once every answer is graded, otherwise the live count.
        grade: gradeStep(toGrade > 0 ? "active" : "done"),
        finish: finishStep("pending"),
        aiSolving: false,
        primary: toGrade > 0 ? `${toGrade} to grade` : "All graded",
        detail: gradeDetail,
      };
    case "finished":
      return {
        kind: "steps",
        score: scoreStep("done"),
        grade: gradeStep("done"),
        finish: finishStep("done"),
        aiSolving: false,
        primary: "Complete",
        detail: "All tasks graded",
      };
    default:
      return {
        kind: "steps",
        score: scoreStep("active"),
        grade: gradeStep("pending"),
        finish: finishStep("pending"),
        aiSolving: false,
        primary: toConfirm > 0 ? `${toConfirm} to confirm` : "Ready to solve",
        detail: scoreDetail,
      };
  }
};

// Placeholder step for the non-`steps` kinds (error/parsing/empty), whose cell
// surfaces never read the step fields.
const PENDING: ExamStep = { state: "pending", value: 0, remaining: 0, total: 0 };
