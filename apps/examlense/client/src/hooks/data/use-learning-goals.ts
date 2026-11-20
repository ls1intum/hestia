import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { createLghCourse, getExamLearningGoals, listLghCourses } from "@/lib/api/api-client";

export const examLearningGoalsKey = (examId: string) =>
  ["exam-learning-goals", examId] as const;

/**
 * The resolved learning goals of an exam's tasks, read from the course goals so
 * a renamed goal shows its new text. Callers should handle `isError` and
 * degrade gracefully — goal ids stay usable on the tasks.
 */
export function useExamLearningGoals(examId: string | undefined) {
  return useQuery({
    queryKey: examLearningGoalsKey(examId ?? ""),
    queryFn: () => getExamLearningGoals(examId!),
    enabled: !!examId,
    staleTime: 60 * 1000,
    retry: 1,
  });
}

export const coursesKey = ["courses"] as const;

/** The courses the user belongs to, for the course pickers (create and move). */
export function useCourses(options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: coursesKey,
    queryFn: listLghCourses,
    enabled: options.enabled ?? true,
    staleTime: 60 * 1000,
    retry: 1,
  });
}

/**
 * Create a new, empty course. Invalidates the course list so a freshly created
 * course shows up the next time a picker opens.
 */
export function useCreateCourse() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => createLghCourse(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: coursesKey });
    },
  });
}
