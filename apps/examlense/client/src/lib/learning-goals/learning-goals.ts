/**
 * Client-side types for learning goals and courses. Goals are derived per task by
 * the backend when a section is confirmed and linked to the task; the frontend
 * only reads them (`/api/exams/{id}/learning-goals`, `/api/lgh/courses`) — see
 * `src/lib/api/api-client.ts` and `src/hooks/data/use-learning-goals.ts`.
 */

export type BloomLevel =
  | "REMEMBER"
  | "UNDERSTAND"
  | "APPLY"
  | "ANALYZE"
  | "EVALUATE"
  | "CREATE";

export type SoloLevel =
  | "PRESTRUCTURAL"
  | "UNISTRUCTURAL"
  | "MULTISTRUCTURAL"
  | "RELATIONAL"
  | "EXTENDED_ABSTRACT";

export interface LearningGoalResponse {
  id: string;
  text: string;
  status?: "PENDING" | "APPROVED" | null;
  bloomLevel?: BloomLevel | null;
  soloLevel?: SoloLevel | null;
}

/** A course of the user, selectable at exam creation. The personal course is "Einzelkram". */
export interface LghCourse {
  id: string;
  name: string;
  role?: "VIEWER" | "EDITOR" | "OWNER";
  personal?: boolean;
}

/** Display name of a course; the personal catch-all course always reads "Einzelkram". */
export const courseLabel = (course: Pick<LghCourse, "name" | "personal">): string =>
  course.personal ? "Einzelkram" : course.name;

export type GoalReviewReason = "GOAL_CHANGED" | "GOAL_REMOVED";

/** Reason shown next to the "prüfen" badge of a task goal whose link waits for the teacher. */
export const goalReviewReasonLabel = (reason: GoalReviewReason | null | undefined): string | null => {
  if (reason === "GOAL_CHANGED") return "Lernziel geändert";
  if (reason === "GOAL_REMOVED") return "Lernziel entfernt";
  return null;
};
