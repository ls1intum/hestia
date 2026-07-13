import { useQuery } from "@tanstack/react-query";
import { listAnswers } from "@/lib/api-client";
import type { TaskAnswer } from "@/lib/grading";

export const taskAnswersKey = (examId: string) =>
  ["task-answers", examId] as const;

export function useTaskAnswers(examId: string | undefined) {
  return useQuery({
    queryKey: examId ? taskAnswersKey(examId) : ["task-answers", "missing"],
    enabled: !!examId,
    queryFn: async () => (await listAnswers(examId!)) as unknown as TaskAnswer[],
  });
}
