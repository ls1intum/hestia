import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { listGrades, upsertGrade } from "@/lib/api-client";
import type { TaskGrade } from "@/lib/grading";

export const taskGradesKey = (examId: string) =>
  ["task-grades", examId] as const;

export function useTaskGrades(examId: string | undefined) {
  return useQuery({
    queryKey: examId ? taskGradesKey(examId) : ["task-grades", "missing"],
    enabled: !!examId,
    queryFn: async () => (await listGrades(examId!)) as unknown as TaskGrade[],
  });
}

export interface UpsertGradeInput {
  task_id: string;
  exam_id: string;
  score: number | null;
  auto_graded: boolean;
  feedback: string | null;
}

export function useUpsertTaskGrade(examId: string | undefined) {
  const qc = useQueryClient();
  return useMutation({
    // graded_by is stamped server-side from the authenticated principal.
    mutationFn: async (input: UpsertGradeInput) => {
      await upsertGrade({
        task_id: input.task_id,
        exam_id: input.exam_id,
        score: input.score,
        auto_graded: input.auto_graded,
        feedback: input.feedback,
      });
    },
    onSuccess: () => {
      if (examId) qc.invalidateQueries({ queryKey: taskGradesKey(examId) });
    },
  });
}
