import type { QueryClient } from "@tanstack/react-query";
import type { ExamListItem } from "@/lib/api/api-client";
import { solveExam } from "@/lib/api/api-solve";
import { toast } from "@/hooks/ui/use-toast";

type RetryableExam = Pick<ExamListItem, "id">;

/**
 * Re-run evaluation for an exam that failed while solving (or was cancelled
 * mid-solve). The backend owns the status transition: `/solve-exam`'s synchronous
 * preflight flips the exam to "evaluating", resets prior answers, and clears the
 * error before dispatching the async solve. This does NOT re-parse, so it never
 * touches the exam's edited structure. Shared by the dashboard row action and the
 * editor error screen. The per-section solve lock makes re-dispatch safe.
 *
 * We AWAIT the dispatch so a rejected request (network / server / preflight)
 * surfaces an error toast instead of a false "restarted" one. Because the backend
 * owns the transition, a failed request leaves the exam in its prior (failed)
 * state rather than stranding it in "evaluating".
 */
export async function retryEvaluation(
  exam: RetryableExam,
  queryClient: QueryClient,
): Promise<void> {
  try {
    await solveExam(exam.id);
  } catch (e) {
    console.error("retry evaluation invoke failed", e);
    toast({
      title: "Couldn't restart evaluation. Please try again.",
      variant: "destructive",
    });
    return;
  }
  queryClient.invalidateQueries({ queryKey: ["exams-list"] });
  queryClient.invalidateQueries({ queryKey: ["exam", exam.id] });
  toast({
    title: "Evaluation restarted — we'll update your dashboard when it's ready.",
  });
}
