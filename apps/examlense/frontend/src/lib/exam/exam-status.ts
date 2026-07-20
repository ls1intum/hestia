import {
  CheckCircle2,
  FileText,
  Gavel,
  Loader2,
  Sparkles,
} from "lucide-react";
import type { LucideIcon } from "lucide-react";
import type { Exam } from "./exam-helpers";

type ExamStatus = Exam["status"];

export interface ExamStatusMeta {
  /** User-facing badge label. */
  label: string;
  /** Badge chip classes (background + text). */
  className: string;
  Icon: LucideIcon;
  /** Whether the icon spins (in-progress states). */
  spin: boolean;
  /** Lifecycle order for the Status-column sort. */
  rank: number;
}

const DRAFT_META: ExamStatusMeta = {
  label: "Draft",
  className: "bg-hestia-warning/10 text-hestia-warning",
  Icon: FileText,
  spin: false,
  rank: 2,
};

/**
 * Single source of truth for how each exam status renders (badge) and sorts
 * (Status column). Both the dashboard badge and the table sort read from here
 * so the mapping — including the `ready`→Draft collapse — can't drift.
 *
 * `failed` is surfaced separately (a warning affordance on the row, its own
 * card variant), so in the badge it deliberately keeps the neutral "Draft"
 * appearance; only its `rank` distinguishes it, sorting failed exams last.
 */
export const EXAM_STATUS_META: Record<ExamStatus, ExamStatusMeta> = {
  parsing: {
    label: "Parsing",
    className: "bg-hestia-primary/10 text-hestia-primary",
    Icon: Loader2,
    spin: true,
    rank: 0,
  },
  evaluating: {
    label: "Evaluating",
    className: "bg-hestia-success/10 text-hestia-success",
    Icon: Sparkles,
    spin: false,
    rank: 1,
  },
  draft: DRAFT_META,
  // `ready` (editable draft ready to send) collapses into Draft for display.
  ready: DRAFT_META,
  grading: {
    label: "Grading",
    className: "bg-hestia-grading/10 text-hestia-grading",
    Icon: Gavel,
    spin: false,
    rank: 3,
  },
  finished: {
    label: "Finished",
    className: "bg-hestia-success/10 text-hestia-success",
    Icon: CheckCircle2,
    spin: false,
    rank: 4,
  },
  // Neutral badge appearance (see note above); sorts last.
  failed: { ...DRAFT_META, rank: 5 },
};

/** Resolve status metadata, collapsing any unknown state to Draft. */
export const examStatusMeta = (status: ExamStatus): ExamStatusMeta =>
  EXAM_STATUS_META[status] ?? DRAFT_META;
