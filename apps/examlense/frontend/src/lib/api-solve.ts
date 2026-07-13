/**
 * Frontend helpers for the Spring Boot solver endpoints.
 *
 * The backend is now the only transport (Supabase is gone), so these always
 * route to Spring.
 */
import { apiRequest } from "@/lib/api-client";

/**
 * Generate an answer for a single task.
 *   - 200 ok      → resolves
 *   - non-2xx     → throws ApiError with the server message
 */
export async function solveTask(taskId: string): Promise<void> {
  await apiRequest<{ ok: boolean }>("/api/solve-task", {
    method: "POST",
    json: { task_id: taskId },
  });
}

/** Solve every task in one section in a single AI call. */
export async function solveSection(
  examId: string,
  sectionId: string | null,
): Promise<void> {
  await apiRequest<{ ok: boolean }>("/api/solve-section", {
    method: "POST",
    json: { exam_id: examId, section_id: sectionId },
  });
}

/** Kick off the per-section orchestrator for the whole exam. */
export async function solveExam(examId: string): Promise<void> {
  await apiRequest<{ ok: boolean }>("/api/solve-exam", {
    method: "POST",
    json: { exam_id: examId },
  });
}
