/**
 * Client-side types for LearningGoalHub data. Goals are derived per task by
 * the LGH service when a section is confirmed (backend-side); the frontend
 * only reads the resolved goals through the backend proxy
 * (`/api/exams/{id}/learning-goals`, `/api/lgh/courses`) — see
 * `src/lib/api-client.ts` and `src/hooks/use-learning-goals.ts`.
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
  id: number;
  text: string;
  status?: "PENDING" | "APPROVED" | null;
  bloomLevel?: BloomLevel | null;
  soloLevel?: SoloLevel | null;
}

/** A LearningGoalHub course, selectable at exam creation. */
export interface LghCourse {
  id: number;
  name: string;
}
