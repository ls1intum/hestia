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

const LGH_TEST_URL = "https://hestia-test.aet.cit.tum.de/learninggoalhub";
const LGH_PROD_URL = "https://hestia.aet.cit.tum.de/learninggoalhub";

/**
 * Public LearningGoalHub web URL for the current deployment. Test and prod ship
 * the *same* frontend bundle (both built from `.env.production`), so the only
 * runtime discriminator is the hostname: local dev and the test host point at
 * the test instance; everything else points at prod.
 */
export const learningGoalHubUrl = (): string => {
  const host = window.location.hostname;
  const isLocal =
    import.meta.env.DEV || host === "localhost" || host === "127.0.0.1";
  const isTest = host === "hestia-test.aet.cit.tum.de";
  return isLocal || isTest ? LGH_TEST_URL : LGH_PROD_URL;
};
