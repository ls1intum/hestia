import { useEffect, useRef } from "react";
import type { ExamListItem } from "@/lib/api/api-client";
import { notifyExamFailure } from "@/components/shared/parse-failure-toast";
import { isParseFailure } from "@/lib/exam/exam-helpers";

/**
 * Fire a slim parse-failure toast whenever a dashboard exam transitions into the
 * `failed` state. Failures already present on first render are recorded silently
 * (so navigating to the page doesn't replay old errors); only exams that newly
 * enter the failed set toast. A retried exam leaves the set and re-toasts if it
 * fails again.
 */
export function useParseFailureToasts(exams?: ExamListItem[]) {
  const failedRef = useRef<Set<string> | null>(null);

  useEffect(() => {
    if (!exams) return;
    const current = new Set(
      exams.filter((e) => e.status === "failed").map((e) => e.id),
    );

    // First run: seed without toasting.
    if (failedRef.current === null) {
      failedRef.current = current;
      return;
    }

    for (const exam of exams) {
      if (exam.status === "failed" && !failedRef.current.has(exam.id)) {
        notifyExamFailure(
          exam.title,
          exam.parse_error,
          isParseFailure(exam),
        );
      }
    }
    failedRef.current = current;
  }, [exams]);
}
