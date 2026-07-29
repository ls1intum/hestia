import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { createLghCourse, getExamLearningGoals, listLghCourses } from "@/lib/api/api-client";

export const examLearningGoalsKey = (examId: string) =>
  ["exam-learning-goals", examId] as const;

/**
 * The resolved learning goals of an exam's tasks, proxied through our backend
 * to LearningGoalHub (VPN-only). Callers should handle `isError` and degrade
 * gracefully — goal ids stay usable on the tasks even when LGH is down.
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

export const lghCoursesKey = ["lgh-courses"] as const;

/**
 * LearningGoalHub course list for the exam-creation course picker, also used as
 * the dashboard's LGH-availability probe: a successful fetch means LGH is
 * reachable, an error means it is down. Pass `refetchInterval` to poll.
 */
export function useLghCourses(
  options: { enabled?: boolean; refetchInterval?: number } = {},
) {
  return useQuery({
    queryKey: lghCoursesKey,
    queryFn: listLghCourses,
    enabled: options.enabled ?? true,
    staleTime: 10 * 60 * 1000,
    retry: 1,
    refetchInterval: options.refetchInterval,
  });
}

/**
 * Create a new, empty LearningGoalHub course. Invalidates the course list so a
 * freshly created course shows up the next time the picker opens.
 */
export function useCreateLghCourse() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => createLghCourse(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: lghCoursesKey });
    },
  });
}
