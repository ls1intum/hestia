import { Navigate } from "react-router-dom";
import {
  examModePath,
  examModeSlug,
  type Exam,
  type ExamModeSlug,
} from "@/lib/exam/exam-helpers";

/**
 * Returns a `<Navigate replace>` to the exam's canonical mode when `exam`'s
 * status doesn't belong in `expected`, else `null`. Call it AFTER the loading
 * and `!exam` guards so `exam` is known to be present.
 *
 * Routing keeps each exam in the single mode its status maps to (see
 * `examModeSlug`); visiting another mode's URL redirects instead of silently
 * transitioning the exam.
 */
export function examModeRedirect(exam: Exam, expected: ExamModeSlug) {
  return examModeSlug(exam.status) !== expected ? (
    <Navigate to={examModePath(exam.id, exam.status)} replace />
  ) : null;
}
