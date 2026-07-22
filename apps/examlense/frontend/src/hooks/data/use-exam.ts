import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { getExam, listTasks, patchTask, ApiError } from "@/lib/api/api-client";
import type { Exam, Task } from "@/lib/exam/exam-helpers";

export const examKey = (id: string) => ["exam", id] as const;
export const tasksKey = (id: string) => ["tasks", id] as const;

export function useExam(id: string | undefined) {
  return useQuery({
    queryKey: id ? examKey(id) : ["exam", "missing"],
    enabled: !!id,
    queryFn: async () => {
      try {
        return (await getExam(id!)) as unknown as Exam;
      } catch (err) {
        // maybeSingle() used to return null for a missing exam.
        if (err instanceof ApiError && err.status === 404) return null;
        throw err;
      }
    },
  });
}

export function useTasks(id: string | undefined) {
  return useQuery({
    queryKey: id ? tasksKey(id) : ["tasks", "missing"],
    enabled: !!id,
    queryFn: async () => (await listTasks(id!)) as unknown as Task[],
  });
}

/**
 * Patch a single task and refresh the exam's task list. Unlike the edit view's
 * `useExamMutations.patchTask`, this does NOT un-confirm the task's section —
 * it is meant for lightweight edits during grading (e.g. correcting a task's
 * max score) that must not disturb the confirmed/solved state.
 */
export function usePatchTask(examId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, patch }: { taskId: string; patch: Partial<Task> }) =>
      patchTask(taskId, patch as Record<string, unknown>),
    onSuccess: () => {
      if (examId) qc.invalidateQueries({ queryKey: tasksKey(examId) });
    },
  });
}
