import { useQuery } from "@tanstack/react-query";
import { getExam, listTasks, ApiError } from "@/lib/api-client";
import type { Exam, Task } from "@/lib/exam-helpers";

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
